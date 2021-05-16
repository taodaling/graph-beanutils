package beans;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

public class BeanUtils {
    private static List<Node> inNodes = new ArrayList<>();
    private static List<Node> allNodes = new ArrayList<>();
    private static volatile Map<Class, Node> classNode = new IdentityHashMap<>();
    private static Map<Class, Node> shadow = classNode;
    private static volatile Map<PairOfClass, Function> direct = new HashMap<>();
    private static volatile Map<PairOfClass, Bridge> bridgeMap = new WeakHashMap<>();
    private static volatile List<Dist[]> prev;
    private static final long WEIGHT_TO_SUPER = 1;
    public static final long INF = (long) 2e18;
    private static volatile BeanErrorHandler handler = new BeanErrorHandler() {
        @Override
        public Object onNotExistingPath(Class src, Class dst, Object val) {
            throw new CastException("Can't find a way to cast " + src.getCanonicalName() +
                    " into " + dst.getCanonicalName());
        }

        @Override
        public Object onExceptionThrow(Class src, Class dst, Object val, Throwable t) {
            if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            }
            if (t instanceof Error) {
                throw (Error) t;
            }
            throw new CastException(t);
        }
    };

    private static class Edge {
        Node src;
        Node dst;
        long weight;
        Function converter;
    }

    private static class Node {
        Class cls;
        int id;
        int iid = -1;
        List<Edge> adj = new ArrayList<>();
        long dist;

        @Override
        public String toString() {
            return "" + cls;
        }
    }

    private static class Dist {
        long dist = INF;
        Edge prev = null;

        boolean update(long dist, Edge prev) {
            if (this.dist <= dist) {
                return false;
            }
            this.dist = dist;
            this.prev = prev;
            return true;
        }

    }

    private static class Bridge {
        Method[] read;
        Method[] write;
        Function[] functions;

        public Bridge(Method[] read, Method[] write, Function[] functions) {
            this.read = read;
            this.write = write;
            this.functions = functions;
        }

        public void copy(Object src, Object target) {
            try {
                for (int i = 0; i < functions.length; i++) {
                    write[i].invoke(target, functions[i].apply(read[i].invoke(src)));
                }
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new CastException(e);
            }
        }
    }

    private static Bridge getBridge(Class sCls, Class dCls) {
        PairOfClass p = new PairOfClass(sCls, dCls);
        Bridge res = bridgeMap.get(p);
        if (res == null) {
            synchronized (BeanUtils.class) {
                res = bridgeMap.get(p);
                if (res != null) {
                    return res;
                }
                res = genBridge(sCls, dCls);
                Map<PairOfClass, Bridge> newBridgeMap = new WeakHashMap<>(bridgeMap);
                newBridgeMap.put(p, res);
                bridgeMap = newBridgeMap;
            }
        }
        return res;
    }

    private static Bridge genBridge(Class sCls, Class dCls) {
        List<Method> read = new ArrayList<>();
        List<Method> write = new ArrayList<>();
        List<Function> func = new ArrayList<>();
        BeanInfo sBean = null;
        BeanInfo dBean = null;
        try {
            sBean = Introspector.getBeanInfo(sCls);
            dBean = Introspector.getBeanInfo(dCls);
        } catch (IntrospectionException e) {
            throw new CastException(e);
        }
        PropertyDescriptor[] sMethod = sBean.getPropertyDescriptors().clone();
        PropertyDescriptor[] dMethod = dBean.getPropertyDescriptors().clone();
        Arrays.sort(sMethod, Comparator.comparing(PropertyDescriptor::getName));
        Arrays.sort(dMethod, Comparator.comparing(PropertyDescriptor::getName));
        for (int i = 0, j = 0; i < sMethod.length; i++) {
            while (j < dMethod.length && sMethod[i].getName().compareTo(dMethod[j].getName()) > 0) {
                j++;
            }
            if (j < dMethod.length && sMethod[i].getName().equals(dMethod[j].getName()) &&
                    sMethod[i].getReadMethod() != null && dMethod[j].getWriteMethod() != null) {
                read.add(sMethod[i].getReadMethod());
                write.add(dMethod[j].getWriteMethod());
                func.add(getCastFunction(sMethod[i].getPropertyType(), dMethod[i].getPropertyType()));
            }
        }
        return new Bridge(read.toArray(new Method[0]), write.toArray(new Method[0]), func.toArray(new Function[0]));
    }

    private static class PairOfClass {
        final Class src;
        final Class dst;

        public PairOfClass(Class src, Class dst) {
            this.src = src;
            this.dst = dst;
        }

        @Override
        public int hashCode() {
            return src.hashCode() * 31 + dst.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof PairOfClass)) {
                return false;
            }
            PairOfClass operand = (PairOfClass) obj;
            return src == operand.src && dst == operand.dst;
        }
    }

    private static Object cast(Node s, Node d, Object src) {
        List<Dist[]> path = prev;
        if (path == null) {
            path = buildGraph();
        }
        if (path.get(s.id)[d.iid] == null) {
            return handler.onNotExistingPath(s.cls, d.cls, src);
        }
        try {
            Node cur = s;
            Object mid = src;
            while (cur != d) {
                Edge e = path.get(cur.id)[d.iid].prev;
                mid = e.converter.apply(src);
                cur = e.dst;
            }
            return mid;
        } catch (Throwable t) {
            return handler.onExceptionThrow(s.cls, d.cls, src, t);
        }
    }

    private static Function wrapExceptionHandler(Class sCls, Class dCls, Function function) {
        return x -> {
            try {
                return function.apply(x);
            } catch (Throwable t) {
                return handler.onExceptionThrow(sCls, dCls, x, t);
            }
        };
    }

    private static Function getCastFunction(Class sCls, Class dCls) {
        if (sCls == dCls) {
            return Function.identity();
        }
        Function res = direct.get(new PairOfClass(sCls, dCls));
        if (res != null) {
            return wrapExceptionHandler(sCls, dCls, res);
        }
        Node s = getNode(sCls);
        Node d = classNode.get(dCls);
        if (d == null) {
            if (dCls.isAssignableFrom(sCls)) {
                return wrapExceptionHandler(sCls, dCls, Function.identity());
            }
            return x -> handler.onNotExistingPath(sCls, dCls, x);
        }
        List<Dist[]> path = prev;
        if (path == null) {
            path = buildGraph();
        }
        if (d.iid < 0 || path.get(s.id)[d.iid].prev == null) {
            return x -> handler.onNotExistingPath(s.cls, d.cls, x);
        }
        Function func = null;
        Node cur = s;
        while (cur != d) {
            Edge e = path.get(cur.id)[d.iid].prev;
            if (func == null) {
                func = e.converter;
            } else {
                func = func.andThen(e.converter);
            }
            cur = e.dst;
        }
        return wrapExceptionHandler(sCls, dCls, func);
    }

    public static Object cast(Class sCls, Class dClass, Object src) {
        return getCastFunction(sCls, dClass).apply(src);
    }

    public static void copy(Object src, Object dst) {
        Objects.requireNonNull(src);
        Objects.requireNonNull(dst);
        getBridge(src.getClass(), dst.getClass()).copy(src, dst);
    }

    synchronized public static void setBeanErrorHandler(BeanErrorHandler beanErrorHandler) {
        handler = beanErrorHandler;
    }

    synchronized public static <S, D> void register(Class<S> s, Class<D> d, Function<S, D> converter,
                                                    long weight) {
        if (weight < 0) {
            throw new IllegalArgumentException();
        }
        weight = Math.min(weight, INF);
        Node u = getNode(s);
        Node v = getNode(d);
        addEdge(u, v, converter, weight);
        setInNode(v);
    }

    synchronized public static <S, D> void registerDirect
            (Class<S> s, Class<D> d, Function<S, D> function) {
        PairOfClass p = new PairOfClass(s, d);
        Map<PairOfClass, Function> newDirect = new HashMap<>(direct);
        newDirect.put(p, function);
        direct = newDirect;
        bridgeMap = new WeakHashMap<>();
    }

    synchronized private static void setInNode(Node x) {
        setInNode0(x);
        prev = null;
        bridgeMap = new WeakHashMap<>();
    }

    private static void setInNode0(Node x) {
        if (x.iid >= 0) {
            return;
        }
        x.iid = inNodes.size();
        inNodes.add(x);
        if (x.cls.getSuperclass() != null) {
            setInNode0(getNode(x.cls.getSuperclass()));
        }
        for (Class face : x.cls.getInterfaces()) {
            setInNode0(getNode(face));
        }
    }

    private static Dist[] batch() {
        Dist[] res = new Dist[inNodes.size()];
        for (int i = 0; i < res.length; i++) {
            res[i] = new Dist();
        }
        return res;
    }

    synchronized private static List<Dist[]> buildGraph() {
        if (prev != null) {
            return prev;
        }
        int n = allNodes.size();
        int m = inNodes.size();
        Dist[][] newPrev = new Dist[n][];
        for (int i = 0; i < n; i++) {
            newPrev[i] = batch();
        }
        //using k(n+k)\log_2n algorithm
        for (int i = 0; i < m; i++) {
            TreeSet<Node> pq = new TreeSet<>(Comparator.<Node>comparingLong(x -> x.dist).thenComparing(x -> x.id));
            for (Node node : allNodes) {
                node.dist = INF;
            }
            inNodes.get(i).dist = 0;
            newPrev[inNodes.get(i).id][i].update(0, null);
            pq.add(inNodes.get(i));
            while (!pq.isEmpty()) {
                Node head = pq.pollFirst();
                for (Edge e : head.adj) {
                    Node node = e.src;
                    if (newPrev[node.id][i].update(head.dist + e.weight, e)) {
                        pq.remove(node);
                        node.dist = head.dist + e.weight;
                        pq.add(node);
                    }
                }
            }
        }

        return prev = new CopyOnWriteArrayList<>(newPrev);
    }

    synchronized private static Edge addEdge(Node u, Node v, Function func, long weight) {
        assert weight <= INF;
        Edge e = new Edge();
        e.src = u;
        e.dst = v;
        e.weight = weight;
        e.converter = func;
        v.adj.add(e);
        return e;
    }

    private static void visitAncestor(Node root) {
        List<Node> update = new ArrayList<>();
        if (root.cls.getSuperclass() != null) {
            Node node = getNode(root.cls.getSuperclass());
            update.add(node);
        }
        for (Class face : root.cls.getInterfaces()) {
            Node node = getNode(face);
            update.add(node);
        }
        for (Node node : update) {
            Edge e = addEdge(root, node, Function.identity(), WEIGHT_TO_SUPER);
            if (prev != null) {
                Dist[] d = getDist(root);
                Dist[] nodeDist = getDist(node);
                for (int i = 0; i < d.length; i++) {
                    d[i].update(nodeDist[i].dist + e.weight, e);
                }
            }
        }
    }

    private static Dist[] getDist(Node node) {
        while (prev.size() <= node.id) {
            Dist[] dist = new Dist[inNodes.size()];
            for (int i = 0; i < dist.length; i++) {
                dist[i] = new Dist();
            }
            prev.add(dist);
        }
        return prev.get(node.id);
    }

    private static Node getNode(Class cls) {
        Node res = classNode.get(cls);
        if (res == null) {
            synchronized (BeanUtils.class) {
                res = shadow.get(cls);
                if (res != null) {
                    return res;
                }
                res = new Node();
                res.id = allNodes.size();
                res.cls = cls;
                allNodes.add(res);

                boolean create = shadow == classNode;
                if (create) {
                    shadow = new IdentityHashMap<>(classNode);
                }

                shadow.put(cls, res);
                visitAncestor(res);

                if (create) {
                    classNode = shadow;
                }
            }
        }
        return res;
    }
}
