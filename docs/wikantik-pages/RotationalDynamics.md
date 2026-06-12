---
cluster: mechanical-engineering
canonical_id: 01KQ0P44VWMP9TGRTGVR01BX69
title: Rotational Dynamics
type: article
tags:
- mechanics
- physics
- rotational-dynamics
- inertia
- angular-momentum
status: active
date: 2025-05-15
summary: A rigorous mathematical derivation of rotational kinematics, the moment of inertia tensor, and the conservation of angular momentum.
auto-generated: false
---

# Rotational Dynamics: Tensor Formalism and Momentum

Rotational dynamics describes the motion of rigid bodies about an axis. It is the rotational analogue of linear dynamics, where force is replaced by torque and mass by the moment of inertia.

## 1. Kinematics of Rotation

For a point at distance $r$from the axis, the relationship between linear velocity ($v$) and angular velocity ($\omega$) is:

$$
\vec{v} = \vec{\omega} \times \vec{r}
$$

The angular acceleration is$\vec{\alpha} = d\vec{\omega}/dt$.
## 2. Moment of Inertia (I)

The Moment of Inertia represents a body's resistance to rotational acceleration. For a discrete system:

$$
I = \sum m_i r_i^2
$$

### 2.1 The Inertia TensorFor a continuous rigid body rotating in 3D space,$I$is a second-rank tensor:

$$
\mathbf{I} = \begin{bmatrix} I_{xx} & I_{xy} & I_{xz} \\ I_{yx} & I_{yy} & I_{yz} \\ I_{zx} & I_{zy} & I_{zz} \end{bmatrix}
$$

Where diagonal elements are moments of inertia and off-diagonals are products of inertia. The kinetic energy of rotation is:

$$
K_{rot} = \frac{1}{2} \vec{\omega}^T \mathbf{I} \vec{\omega}
$$

### 2.2 Parallel Axis Theorem

$$
I = I_{cm} + Md^2
$$

Where$I_{cm}$is the moment of inertia about the center of mass and$d$is the distance to the parallel axis.## 3. Angular Momentum (L)

Angular momentum is the rotational analogue of linear momentum:

$$
\vec{L} = \mathbf{I} \vec{\omega}
$$

For a point mass:$\vec{L} = \vec{r} \times \vec{p}$.
### 3.1 Conservation of Angular Momentum
In the absence of an external torque ($\vec{\tau}_{ext} = 0$):

$$
\frac{d\vec{L}}{dt} = 0 \Rightarrow \vec{L}_{initial} = \vec{L}_{final}
$$

*Application:* A figure skater pulls their arms in (decreasing$I$), which forces$\omega$to increase to maintain constant$L$.
## 4. Torque and Euler's Equations

Torque is the rate of change of angular momentum:

$$
\vec{\tau} = \frac{d\vec{L}}{dt} = \mathbf{I}\vec{\alpha} + \vec{\omega} \times (\mathbf{I}\vec{\omega})
$$

### 4.1 Euler's Equations for a Rigid BodyIn the principal axes frame:

$$
I_1 \dot{\omega}_1 - (I_2 - I_3)\omega_2 \omega_3 = \tau_1
$$

$$
I_2 \dot{\omega}_2 - (I_3 - I_1)\omega_3 \omega_1 = \tau_2
$$

$$
I_3 \dot{\omega}_3 - (I_1 - I_2)\omega_1 \omega_2 = \tau_3
$$

## 5. Summary Table: Linear vs. Rotational
| Linear Quantity | Rotational Analogue | Relationship |
| :--- | :--- | :--- |
| Position ($x$) | Angle ($\theta$) |$s = \theta r$|
| Velocity ($v$) | Angular Velocity ($\omega$) |$v = \omega r$|
| Acceleration ($a$) | Angular Acceleration ($\alpha$) |$a = \alpha r$|
| Mass ($m$) | Moment of Inertia ($I$) |$I = \int r^2 dm$|
| Force ($F$) | Torque ($\tau$) |$\tau = r F \sin\theta$|
| Momentum ($p$) | Angular Momentum ($L$) |$L = I \omega$ |

Understanding the tensor nature of inertia is critical for analyzing complex rotations in aerospace engineering, robotics, and biomechanics.
