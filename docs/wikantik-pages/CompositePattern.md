---
cluster: design-patterns
canonical_id: 01KQ0P44NQRBAKEZ4ZY5M9BMER
title: Composite Pattern
type: article
tags:
- composite
- structure
- tree
- java
summary: Concrete implementation of the Composite pattern for modeling hierarchical structures like file systems or UI component trees.
auto-generated: false
date: 2025-05-15
---

# Composite Pattern: Hierarchical Structural Modeling

The Composite pattern treats individual objects and compositions of objects uniformly. It is the architectural foundation for tree structures, allowing clients to ignore the difference between a "Leaf" (terminal node) and a "Composite" (container node).

## 1. Concrete Implementation: File System Explorer

In a file system, both `File` and `Directory` share a common interface for operations like `getSize()` or `list()`.

### 1.1 The Component Interface

```java
import java.util.List;

public interface FileSystemNode {
    String getName();
    long getSize();
    void print(String indent);
}
```

### 1.2 The Leaf (File)

```java
public record File(String name, long size) implements FileSystemNode {
    @Override
    public String getName() { return name; }
    
    @Override
    public long getSize() { return size; }

    @Override
    public void print(String indent) {
        System.out.println(indent + "📄 " + name + " (" + size + " bytes)");
    }
}
```

### 1.3 The Composite (Directory)

The Composite node manages a collection of components and recursively delegates operations.

```java
import java.util.ArrayList;
import java.util.List;

public class Directory implements FileSystemNode {
    private final String name;
    private final List<FileSystemNode> children = new ArrayList<>();

    public Directory(String name) {
        this.name = name;
    }

    public void add(FileSystemNode node) {
        children.add(node);
    }

    @Override
    public String getName() { return name; }

    @Override
    public long getSize() {
        return children.stream()
                       .mapToLong(FileSystemNode::getSize)
                       .sum();
    }

    @Override
    public void print(String indent) {
        System.out.println(indent + "📁 " + name);
        for (FileSystemNode child : children) {
            child.print(indent + "  ");
        }
    }
}
```

## 2. Practitioner Insights

### 2.1 The Transparency vs. Safety Trade-off
*   **Transparency:** Define `add()`/`remove()` in the base interface. This allows clients to treat all nodes identically but forces Leaf nodes to throw `UnsupportedOperationException`.
*   **Safety:** Define management methods only in the `Composite` class. This prevents runtime errors on Leaves but requires clients to cast or use type checking before modifying the tree.

### 2.2 Recursion Depth
For extremely deep trees, recursive implementation of `getSize()` can lead to `StackOverflowError`. In such cases, use an iterative traversal with an explicit `java.util.Deque` as a stack.

### 2.3 Caching Results
In large, static trees, the overhead of calculating `getSize()` recursively can be significant. Implementing a "dirty" flag or caching the size at the Composite level improves performance for read-heavy workloads.

### 2.4 Synergy with Visitor
If you need to add many new operations (e.g., `compress()`, `calculateChecksum()`), use the [Visitor Pattern](VisitorPattern) to avoid polluting the `FileSystemNode` interface and breaking the Open/Closed Principle.
