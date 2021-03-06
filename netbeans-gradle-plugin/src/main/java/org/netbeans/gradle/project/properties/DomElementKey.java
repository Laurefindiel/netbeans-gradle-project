package org.netbeans.gradle.project.properties;

public final class DomElementKey implements Comparable<DomElementKey> {
    private final String name;
    private final String namespace;

    public DomElementKey(String elementName, String namespace) {
        this.name = elementName;
        this.namespace = namespace;
    }

    public String getName() {
        return name;
    }

    public String getNamespace() {
        return namespace;
    }

    @Override
    public int hashCode() {
        int hash = 7;
        hash = 31 * hash + (this.name != null ? this.name.hashCode() : 0);
        hash = 31 * hash + (this.namespace != null ? this.namespace.hashCode() : 0);
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;

        final DomElementKey other = (DomElementKey)obj;
        if ((this.name == null) ? (other.name != null) : !this.name.equals(other.name))
            return false;
        if ((this.namespace == null) ? (other.namespace != null) : !this.namespace.equals(other.namespace))
            return false;
        return true;
    }

    private int nullSafeStrCmp(String str1, String str2) {
        if (str1 == null) {
            return str2 != null ? -1 : 0;
        }
        else if (str2 == null) {
            return 1;
        }

        return str1.compareTo(str2);
    }

    @Override
    public int compareTo(DomElementKey o) {
        int namespaceCmp = nullSafeStrCmp(namespace, o.namespace);
        if (namespaceCmp != 0) {
            return namespaceCmp;
        }

        return nullSafeStrCmp(name, o.name);
    }
}
