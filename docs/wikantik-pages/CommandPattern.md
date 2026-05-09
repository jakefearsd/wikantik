---
cluster: design-patterns
canonical_id: 01KQ0P44NNSB64CQ00HDDTVW4K
title: Command Pattern
type: article
tags:
- command
- java-21
- undo
- design-patterns
summary: Concrete Java 21 implementation of the Command pattern featuring transactional undo/redo capabilities using modern language features.
auto-generated: false
date: 2025-05-15
---

# Command Pattern: Transactional Undo/Redo in Java 21

The Command pattern decouples an invoker from a receiver by encapsulating a request as a standalone object. While textbook examples often stop at simple execution, production-grade implementations must handle state snapshots, rollback integrity, and history management.

In Java 21, we leverage **Sealed Interfaces**, **Records**, and **Pattern Matching** to build a type-safe, transactional command system.

## 1. The Command Interface

We define a `Command` as a sealed interface to restrict the implementation hierarchy and enable exhaustive pattern matching in the history manager if needed.

```java
public sealed interface Command permits TextInsertCommand, StyleChangeCommand {
    void execute();
    void undo();
}
```

## 2. Concrete Implementation: Text Editor Example

To implement an undoable command, the object must capture the **Receiver's state** *before* the mutation occurs.

```java
public record TextInsertCommand(
    Document receiver,
    int offset,
    String content,
    // Captured state for undo
    AtomicReference<String> previousState
) implements Command {

    public TextInsertCommand(Document receiver, int offset, String content) {
        this(receiver, offset, content, new AtomicReference<>());
    }

    @Override
    public void execute() {
        // Capture state before mutation
        previousState.set(receiver.getTextRange(offset, content.length()));
        receiver.insert(offset, content);
    }

    @Override
    public void undo() {
        String prev = previousState.get();
        if (prev != null) {
            receiver.replace(offset, content.length(), prev);
        }
    }
}
```

## 3. The Command History Manager

A robust history manager uses two stacks (implemented via `Deque`) to manage the LIFO nature of undo/redo.

```java
import java.util.ArrayDeque;
import java.util.Deque;

public class HistoryManager {
    private final Deque<Command> undoStack = new ArrayDeque<>();
    private final Deque<Command> redoStack = new ArrayDeque<>();

    public void execute(Command command) {
        command.execute();
        undoStack.push(command);
        redoStack.clear(); // Mandatory: New actions invalidate forward history
    }

    public void undo() {
        if (!undoStack.isEmpty()) {
            Command command = undoStack.pop();
            command.undo();
            redoStack.push(command);
        }
    }

    public void redo() {
        if (!redoStack.isEmpty()) {
            Command command = redoStack.pop();
            command.execute();
            undoStack.push(command);
        }
    }
}
```

## 4. Key Practitioner Insights

### 4.1 State Capture: Memento vs. Inverse
*   **Memento:** Store a full or partial snapshot (like `previousState` above). Use this when the action is destructive or non-deterministic.
*   **Inverse Operation:** Calculate the reverse (e.g., `execute: add(5)`, `undo: subtract(5)`). This saves memory but risks drift if state transitions aren't perfectly commutative.

### 4.2 Handling Exceptions
Commands should be transactional. If `execute()` fails halfway through a `MacroCommand` (a composite of multiple commands), you must iterate backwards through the successfully executed sub-commands and call `undo()` on each.

### 4.3 Serialization
For persistent history, commands should be serializable (e.g., via Jackson or Protobuf). Store the command parameters and use a factory to reconstitute the `Receiver` references upon hydration.

### 4.4 Thread Safety
In concurrent environments, the `HistoryManager` must synchronize access to the stacks. However, the `Command` objects themselves should ideally be immutable (except for the captured state) to prevent race conditions during execution.
