---
canonical_id: 01KQPQXFJDMBE25NN63K3THWK0
date: 2026-05-03T00:00:00Z
cluster: computer-science-foundations
tags:
- computer-science
- binary
- arithmetic
- bitwise
- logic
- architecture
title: Binary Arithmetic
summary: A foundational guide to binary arithmetic, covering addition, subtraction
  using two's complement, and the fundamental bitwise operations (AND, OR, XOR, NOT,
  shifts). Essential for understanding low-level computer architecture and digital
  logic.
status: active
---

# Binary Arithmetic

Binary arithmetic is the foundation of modern digital computing. Since electronic circuits (transistors) are most naturally operated in two states—on or off—computers represent all data and perform all calculations using the base-2 (binary) system.

## 1. Binary Addition

Binary addition follows the same positional logic as decimal addition, but with only two possible digits: 0 and 1.

The four basic rules are:
- `0 + 0 = 0`
- `0 + 1 = 1`
- `1 + 0 = 1`
- `1 + 1 = 10` (write 0, carry 1)
- `1 + 1 + 1 (carry) = 11` (write 1, carry 1)

### Example: `5 + 3`
```text
  111  (carries)
   101 (5)
+  011 (3)
------
  1000 (8)
```

## 2. Binary Subtraction (Two's Complement)

While subtraction can be performed directly, modern computers use **Two's Complement** to represent negative numbers. This allows the CPU to perform subtraction using the same hardware circuits as addition.

### How to find the Two's Complement of a number:
1.  **Invert** all the bits (0 becomes 1, 1 becomes 0).
2.  **Add 1** to the result.

### Example: `5 - 3` (using 4-bit representation)
- `5` is `0101`
- `3` is `0011`
- To find `-3`:
  - Invert `0011` -> `1100`
  - Add `1` -> `1101` (This is `-3` in two's complement)
- Perform `5 + (-3)`:
  ```text
    111  (carries)
     0101 (5)
  +  1101 (-3)
  -------
    10010 (The 5th bit is discarded in 4-bit math)
  ```
- Result: `0010` (which is `2`).

## 3. Bitwise Operations

Bitwise operations are used to manipulate individual bits within a word. They are extremely fast and essential for low-level programming (drivers, graphics, cryptography).

### Logical Operators
- **AND (`&`)**: Result is 1 only if both bits are 1. (Used for masking/clearing bits).
- **OR (`|`)**: Result is 1 if at least one bit is 1. (Used for setting bits).
- **XOR (`^`)**: Result is 1 only if the bits are different. (Used for toggling bits or simple encryption).
- **NOT (`~`)**: Inverts all bits.

### Shift Operators
- **Logical Left Shift (`<<`)**: Shifts bits to the left, filling with 0s. Effectively multiplies by powers of 2.
- **Logical Right Shift (`>>`)**: Shifts bits to the right. Effectively divides by powers of 2.

## See Also
- [ComputerScienceFoundationsHub](ComputerScienceFoundationsHub)
- [OperatingSystems](OperatingSystems)
- [DataStructuresHub](DataStructuresHub)
