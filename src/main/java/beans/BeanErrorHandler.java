package beans;

public interface BeanErrorHandler {
    Object onNotExistingPath(Class src, Class dst, Object val);

    Object onExceptionThrow(Class src, Class dst, Object val, Throwable t);
}
