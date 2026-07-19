package ContainerTests;

import java.io.Serializable;
import java.util.Objects;

public class TestableObject implements Serializable {
    private static final long serialVersionUID = 1L;

    public int id;
    public String name;
    public boolean flag;

    public TestableObject() {}

    public TestableObject(int id, String name, boolean flag) {
        this.id = id;
        this.name = name;
        this.flag = flag;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TestableObject that = (TestableObject) o;
        return id == that.id && flag == that.flag && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, flag);
    }

    @Override
    public String toString() {
        return "TestableObject{" + "id=" + id + ", name='" + name + '\'' + ", flag=" + flag + '}';
    }
}
