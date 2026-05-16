---
canonical_id: 01KRQG0KJR439FNQSDJ0N1ZSHK
type: article
tags:
- design-patterns
- object-oriented-programming
- visitor-pattern
- gang-of-four
title: Visitor Pattern
relations:
- type: component_of
  target_id: 01KQ0P44PK0X731A1NYX3X5B9G
- type: related_to
  target_id: CompositePattern
summary: Technical guide to the Visitor Design Pattern. Explains how to separate algorithms
  from the object structure on which they operate, utilizing Double Dispatch.
status: active
date: '2026-05-15'
cluster: software-engineering
---

# Visitor Pattern

The **Visitor Pattern** is a behavioral Gang of Four (GoF) design pattern that allows you to add new operations or algorithms to an existing object structure without modifying those objects.

## 1. The Problem
Imagine you have a complex tree structure of objects (like an Abstract Syntax Tree in a compiler, or a Document Object Model). You need to perform various operations across this tree: exporting it to XML, extracting metrics, or applying a transformation. 
If you add an `exportXML()` and `extractMetrics()` method to every single node class, you violate the **Single Responsibility Principle** and pollute the data classes with unrelated business logic.

## 2. The Solution: Double Dispatch
The Visitor pattern extracts these operations into a separate class called a `Visitor`.

It relies on a technique called **Double Dispatch**. In standard Object-Oriented languages (like Java or C#), method overloading is resolved at compile-time (Single Dispatch). The Visitor pattern uses two method calls to ensure the runtime executes the correct code based on *both* the type of the Visitor and the specific type of the Element.

1.  **The Element**: Every node in the structure implements an `accept(Visitor v)` method.
2.  **The Accept Implementation**: Inside `accept`, the element calls `v.visit(this)`. Because `this` is strongly typed to the specific element class at compile-time, the correct overloaded `visit()` method is triggered on the Visitor.

## 3. Pros and Cons
*   **Pros**: You can add entirely new behaviors to a complex object graph simply by creating one new Visitor class. It perfectly adheres to the Open/Closed Principle.
*   **Cons**: If the underlying object hierarchy changes (e.g., you add a new Element type), you must update every single Visitor class to support the new element. It makes modifying the structure very brittle.

The Visitor pattern is almost exclusively used in conjunction with the [Composite Pattern](CompositePattern), acting as the engine that traverses the composite tree.
