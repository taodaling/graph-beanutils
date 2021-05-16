package examples;

import beans.BeanUtils;
import beans.CastException;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.Function;

public class BeanCopy {
    public static void main(String[] args) {
        Class[] primitive = new Class[]{
                byte.class, boolean.class, short.class, char.class,
                int.class, float.class, double.class, long.class
        };
        Class[] boxed = new Class[]{
                Byte.class, Boolean.class, Short.class, Character.class,
                Integer.class, Float.class, Double.class, Long.class
        };
        Object[] defaultVal = new Object[]{
                (byte) 0, false, (short) 0, (char) 0,
                0, 0f, 0d, 0L
        };
        for (int i = 0; i < 8; i++) {
            int finalI = i;
            BeanUtils.register(primitive[i], boxed[i], Function.identity(), 0);
            BeanUtils.register(boxed[i], primitive[i], x -> x == null ? defaultVal[finalI] : x, 0);
        }
        BeanUtils.register(Object.class, String.class, Object::toString, 1000);
        BeanUtils.register(Number.class, Integer.class, Number::intValue, 100);
        BeanUtils.register(Number.class, Byte.class, Number::byteValue, 100);
        BeanUtils.register(Number.class, Short.class, Number::shortValue, 100);
        BeanUtils.register(Number.class, Float.class, Number::floatValue, 100);
        BeanUtils.register(Number.class, Double.class, Number::doubleValue, 100);
        BeanUtils.register(Number.class, Long.class, Number::longValue, 100);
        BeanUtils.register(Short.class, Character.class, x -> (char) x.shortValue(), 100);


        ThreadLocal<SimpleDateFormat> sdf = ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));
        BeanUtils.register(Long.class, Date.class, Date::new, 100);
        BeanUtils.register(Date.class, String.class, x -> sdf.get().format(x), 1000);
        BeanUtils.register(String.class, Date.class, x -> {
            try {
                return sdf.get().parse(x);
            } catch (ParseException e) {
                throw new CastException(e);
            }
        }, 1000);
        BeanUtils.register(Date.class, Long.class, Date::getTime, 100);
        BeanUtils.register(Object[].class, List.class, Arrays::asList, 100);
        BeanUtils.register(Collection.class, HashSet.class, HashSet::new, 100);
        BeanUtils.register(Collection.class, ArrayList.class, ArrayList::new, 100);
        BeanUtils.register(Collection.class, ArrayDeque.class, ArrayDeque::new, 100);
        BeanUtils.register(Collection.class, Object[].class, x -> x.toArray(new Object[0]), 100);

        Bean a = new Bean();
        a.a0 = new Date();
        a.a1 = 1;
        a.a2 = 100L;
        a.a3 = new int[0];
        a.id = new Object[]{1, 1, 2, 3};

        ToBean b = new ToBean();
        BeanUtils.copy(a, b);
        System.out.println(a);
        System.out.println(b);
    }

    public static class Bean {
        Date a0;
        int a1;
        Long a2;
        Object a3;
        Object[] id;

        public Date getA0() {
            return a0;
        }

        public void setA0(Date a0) {
            this.a0 = a0;
        }

        public int getA1() {
            return a1;
        }

        public void setA1(int a1) {
            this.a1 = a1;
        }

        public Long getA2() {
            return a2;
        }

        public void setA2(Long a2) {
            this.a2 = a2;
        }

        public Object getA3() {
            return a3;
        }

        public void setA3(Object a3) {
            this.a3 = a3;
        }

        public Object[] getId() {
            return id;
        }

        public void setId(Object[] id) {
            this.id = id;
        }

        @Override
        public String toString() {
            return "Bean{" +
                    "a0=" + a0 +
                    ", a1=" + a1 +
                    ", a2=" + a2 +
                    ", a3=" + a3 +
                    ", id=" + Arrays.toString(id) +
                    '}';
        }
    }

    public static class ToBean {
        String a0;
        long a1;
        Character a2;
        String a3;
        Set<Integer> id;

        public String getA0() {
            return a0;
        }

        public void setA0(String a0) {
            this.a0 = a0;
        }

        public long getA1() {
            return a1;
        }

        public void setA1(long a1) {
            this.a1 = a1;
        }

        public Character getA2() {
            return a2;
        }

        public void setA2(Character a2) {
            this.a2 = a2;
        }

        public String getA3() {
            return a3;
        }

        public void setA3(String a3) {
            this.a3 = a3;
        }

        public Set<Integer> getId() {
            return id;
        }

        public void setId(Set<Integer> id) {
            this.id = id;
        }

        @Override
        public String toString() {
            return "ToBean{" +
                    "a0='" + a0 + '\'' +
                    ", a1=" + a1 +
                    ", a2=" + a2 +
                    ", a3='" + a3 + '\'' +
                    ", id=" + id +
                    '}';
        }
    }
}
