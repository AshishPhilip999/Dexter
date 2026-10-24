package ContainerCore.Container;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

import StorageStatus.SP.SPFail;
import StorageStatus.SP.SPStatus;
import StorageStatus.SP.SPResult;

/*
Result of a Container / KeyContainer operation: an SPResult (status, bytes,
error -- all inherited unchanged) that also carries the value as an object of
the container's type.

    ContainerResult<User> result = users.read(42);
    if (result.hasSucceeded()) {
        User user = result.getValue();
    } else if (result.getStatus() == SPFail.KEY_NOT_FOUND) {
        ...
    }

getValue() by operation:
    write(value) / write(key, value)   the value written
    read() / read(key)                 the value read (keyed read(): the first)
    delete(key)                        the value that was removed
    delete(), recount(), failures      null
getValues() gives every value: all records for a keyed read(), otherwise a
list of getValue() (empty when it is null).

The factories are named from / fromAll / failed because SPResult's of /
ofError are inherited (and still return a plain SPResult).
*/
public final class ContainerResult<T extends Serializable> extends SPResult {
    private final T value;
    private final List<T> values;

    private ContainerResult(SPResult result, T value, List<T> values) {
        super(result);
        this.value = value;
        if (values != null) {
            this.values = Collections.unmodifiableList(values);
        } else {
            this.values = value == null ? Collections.emptyList() : Collections.singletonList(value);
        }
    }

    /*
    A storage result with the value it is about.
    */
    public static <T extends Serializable> ContainerResult<T> from(SPResult result, T value) {
        return new ContainerResult<>(result, value, null);
    }

    /*
    A storage result without a value (failures, delete(), recount()).
    */
    public static <T extends Serializable> ContainerResult<T> from(SPResult result) {
        return new ContainerResult<>(result, null, null);
    }

    /*
    A storage result with several values (a keyed read()); getValue() is the first.
    */
    public static <T extends Serializable> ContainerResult<T> fromAll(SPResult result, List<T> values) {
        T first = values == null || values.isEmpty() ? null : values.get(0);
        return new ContainerResult<>(result, first, values);
    }

    /*
    A result with only a status (e.g. SPFail.LIMIT_EXCEEDED).
    */
    public static <T extends Serializable> ContainerResult<T> failed(SPStatus status) {
        return new ContainerResult<>(SPResult.of(status), null, null);
    }

    /*
    A failure caused by an exception (e.g. SERIALIZE_ERROR, DESERIALIZE_ERROR).
    */
    public static <T extends Serializable> ContainerResult<T> failed(SPFail status, Exception error) {
        return new ContainerResult<>(SPResult.ofError(status, error), null, null);
    }

    /*
    The value the operation was about, as an object of the container's type;
    null if there is none (see the class comment).
    */
    public T getValue() {
        return this.value;
    }

    /*
    Every value: all records for a keyed read(), otherwise getValue() as a list
    (empty when there is none). Unmodifiable.
    */
    public List<T> getValues() {
        return this.values;
    }

    @Override
    public String toString() {
        if (this.getError() != null || this.value == null) {
            return super.toString();
        }
        if (this.getDataArray() != null) {
            return this.getStatus() + " (" + this.values.size() + " values)";
        }
        return this.getStatus() + " (" + this.value + ")";
    }
}
