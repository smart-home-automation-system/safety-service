package cloud.cholewa.safety.registry;

//a read of database-service that did not end with the list asked for; never answered as such: a failed refresh keeps the
//detectors the service had (a WARN)
public class RegistryCallException extends RuntimeException {

    public RegistryCallException(final String message) {
        super(message);
    }
}
