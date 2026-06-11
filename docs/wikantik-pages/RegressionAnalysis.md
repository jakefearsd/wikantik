---
title: Regression Analysis
type: article
cluster: mathematics
status: active
date: '2026-05-06'
summary: A detailed exploration of modeling relationships through orthogonal projection, matrix formulation of OLS, and the geometry of the hat matrix.
tags: [mathematics, statistics, regression, linear-regression, machine-learning, prediction]
related: [StatisticsFundamentals, StatisticalInference, LinearAlgebra, MathematicsHub]
---

# Regression Analysis: The Geometry of Projection

Regression analysis is the primary statistical tool for modeling the relationship between a dependent variable $y$and one or more independent variables$X$. At its mathematical heart, linear regression is an exercise in **orthogonal projection** within a high-dimensional vector space.

## 1. Ordinary Least Squares (OLS)

The goal of OLS is to find the vector of coefficients$\boldsymbol{\beta}$that minimizes the sum of squared residuals.

### 1.1 The Matrix Formulation
Given$n$observations and$k$predictors, we define the model in matrix form:

$$
\mathbf{y} = \mathbf{X}\boldsymbol{\beta} + \boldsymbol{\epsilon}
$$

-$\mathbf{y} \in \mathbb{R}^n$: The vector of observations.-$\mathbf{X} \in \mathbb{R}^{n \times k}$: The design matrix of predictors.
-$\boldsymbol{\beta} \in \mathbb{R}^k$: The vector of unknown coefficients.
-$\boldsymbol{\epsilon} \in \mathbb{R}^n$: The vector of errors.

The OLS solution that minimizes$\parallel \mathbf{y} - \mathbf{X}\boldsymbol{\beta} \parallel^2$is found by solving the **Normal Equations**:

$$
\mathbf{X}^T \mathbf{X} \hat{\boldsymbol{\beta}} = \mathbf{X}^T \mathbf{y}
$$
$$
\hat{\boldsymbol{\beta}} = (\mathbf{X}^T \mathbf{X})^{-1} \mathbf{X}^T \mathbf{y}
$$

## 2. Geometric Intuition: Orthogonal Projection
The most powerful way to understand regression is through the geometry of linear algebra.

### 2.1 The Column Space of X
The matrix$\mathbf{X}$defines a subspace in$\mathbb{R}^n$called the **Column Space** (or Range) of$\mathbf{X}$, denoted$\text{Col}(\mathbf{X})$. Any prediction$\hat{\mathbf{y}} = \mathbf{X}\boldsymbol{\beta}$must lie within this subspace.
Because the observed vector$\mathbf{y}$likely contains noise, it does not lie exactly within$\text{Col}(\mathbf{X})$.

### 2.2 The Projection (Hat) Matrix
The "best fit"$\hat{\mathbf{y}}$is the point in$\text{Col}(\mathbf{X})$that is **closest** to$\mathbf{y}$in terms of Euclidean distance. Geometrically, this is the **orthogonal projection** of$\mathbf{y}$onto the column space.
The transformation that performs this projection is the **Hat Matrix**$\mathbf{H}$:

$$
\hat{\mathbf{y}} = \mathbf{H}\mathbf{y}
$$
$$
\mathbf{H} = \mathbf{X}(\mathbf{X}^T \mathbf{X})^{-1} \mathbf{X}^T
$$

- **Properties of H:** It is symmetric ($\mathbf{H}^T = \mathbf{H}$) and idempotent ($\mathbf{H}^2 = \mathbf{H}$).- **The Residual Vector:**$\mathbf{e} = \mathbf{y} - \hat{\mathbf{y}} = (\mathbf{I} - \mathbf{H})\mathbf{y}$. Geometrically, the residuals are the vector component of$\mathbf{y}$that is orthogonal to the column space of$\mathbf{X}$.

## 3. Quantitative Foundations: The Gauss-Markov Theorem

Why is OLS so widely used? The Gauss-Markov theorem provides the justification.

### 3.1 BLUE (Best Linear Unbiased Estimator)
Under the assumptions of linearity, full rank, exogeneity ($\mathbb{E}[\epsilon|X] = 0$), and homoscedasticity ($\text{Var}(\epsilon) = \sigma^2 I$), the OLS estimator$\hat{\boldsymbol{\beta}}$is the **Best Linear Unbiased Estimator**. 
"Best" here means that it has the **minimum variance** among all linear unbiased estimators.

#### Table 1: ANOVA for Linear Regression
| Source | Degrees of Freedom | Sum of Squares (SS) | Mean Square (MS) |
| :--- | :--- | :--- | :--- |
| **Model** |$k - 1$|$\sum (\hat{y}_i - \bar{y})^2$|$\text{SSM} / (k-1)$|
| **Error** |$n - k$|$\sum (y_i - \hat{y}_i)^2$|$\text{SSE} / (n-k)$|
| **Total** |$n - 1$|$\sum (y_i - \bar{y})^2$| |

The Ratio$F = \text{MSM} / \text{MSE}$allows us to test if the model as a whole is statistically significant.

## 4. Real-World Applications

### 4.1 Finance: The Capital Asset Pricing Model (CAPM)
In finance, regression is used to calculate the risk of an asset. The "Beta" ($\beta$) of a stock is the slope of a linear regression where the market return is the independent variable and the stock return is the dependent variable. A$\beta > 1$indicates the stock is more volatile than the market (leveraged projection).

### 4.2 Machine Learning and Regularization
When the number of predictors$k$is large, the matrix$\mathbf{X}^T \mathbf{X}$may be ill-conditioned (nearly singular). This corresponds to the geometric problem of a very "thin" or "flat" column space. 
**Ridge Regression** solves this by adding$\lambda \mathbf{I}$ to the diagonal, which geometrically "inflates" the subspace and prevents the coefficients from exploding, a direct application of Bayesian reasoning (Gaussian prior).

## See Also
- [StatisticsFundamentals]
- [StatisticalInference]
- [LinearAlgebra]
- [MathematicsHub]