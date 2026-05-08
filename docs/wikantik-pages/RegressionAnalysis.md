---
title: Regression Analysis
type: article
cluster: mathematics
status: active
date: '2026-05-06'
summary: A detailed exploration of modeling relationships between variables, covering linear and logistic regression, model evaluation, and common pitfalls.
tags: [mathematics, statistics, regression, linear-regression, machine-learning, prediction]
related: [StatisticsFundamentals, StatisticalInference, LinearAlgebra, MathematicsHub]
---

# Regression Analysis: Modeling Relationships

Regression analysis is a set of statistical processes for estimating the relationships between a **dependent variable** (often called the 'outcome' or 'label') and one or more **independent variables** (often called 'predictors' or 'features').

It is the primary tool for prediction, forecasting, and determining which factors matter most in a complex system.

---

## I. Linear Regression

Linear regression assumes a linear relationship between the input $x$ and the output $y$.

### 1.1 Simple Linear Regression
The model is defined by:
$$ y = \beta_0 + \beta_1 x + \epsilon $$
*   $\beta_0$: The intercept (value of $y$ when $x=0$).
*   $\beta_1$: The slope (how much $y$ changes for each unit of $x$).
*   $\epsilon$: The error term (residual), representing noise or omitted factors.

### 1.2 The Method of Least Squares
We find the "best fit" line by minimizing the **Sum of Squared Residuals (SSR)**. This ensures that the line passes as close as possible to all data points.

### 1.3 Evaluating the Model
*   **$R^2$ (Coefficient of Determination)**: Represents the proportion of variance in $y$ that is explained by $x$. $R^2 = 1.0$ is a perfect fit; $R^2 = 0.0$ means the model explains nothing.
*   **p-values for Coefficients**: Tells us if the relationship between $x$ and $y$ is statistically significant.

---

## II. Multiple Linear Regression

Most real-world phenomena have multiple causes:
$$ y = \beta_0 + \beta_1 x_1 + \beta_2 x_2 + \dots + \beta_n x_n + \epsilon $$

### 2.1 Confounding Variables
If you omit a variable that is correlated with both $x$ and $y$, your coefficients will be biased.
*   **Example**: Analyzing the relationship between "Coffee Consumption" and "Heart Disease." If you don't include "Smoking" as a variable, coffee might look like it causes heart disease simply because smokers often drink more coffee.

### 2.2 Multicollinearity
When two independent variables are highly correlated with each other (e.g., "Height in inches" and "Height in centimeters"). This makes it difficult for the model to "assign credit" to the correct variable, leading to unstable coefficients.

---

## III. Logistic Regression

Despite the name, logistic regression is used for **Classification**, not for predicting a continuous value. It predicts the *probability* that an observation belongs to a particular category (e.g., Yes/No, Spam/Not Spam).

### 3.1 The Logistic (Sigmoid) Function
Linear regression can predict values from $-\infty$ to $+\infty$. To get a probability, we map the linear output through the sigmoid function:
$$ P(y=1) = \frac{1}{1 + e^{-(\beta_0 + \beta_1 x)}} $$
This forces the output to be between 0 and 1.

---

## IV. Common Pitfalls in Regression

### 4.1 "Correlation is not Causation"
Regression shows how variables move together. It does not prove that $x$ *causes* $y$.
*   *Real-World Example*: Ice cream sales are highly correlated with shark attacks. However, ice cream does not cause shark attacks; "Warm Weather" causes both.

### 4.2 Overfitting
Adding too many variables to a model can make it fit the noise in your specific data set perfectly, but it will fail to generalize to new data. This is the central challenge in **Machine Learning**.

### 4.3 Extrapolation
Predicting outside the range of your data. If you have data on house prices from \$200k to \$800k, your model may be completely wrong if you try to use it for a \$5M mansion.

---

## V. Real-World Applications

### 5.1 Real Estate: Automated Valuation Models (AVMs)
Platforms like Zillow use multiple regression to estimate house prices. Features ($x$) include square footage, number of bathrooms, zip code, and school ratings. The outcome ($y$) is the predicted price.

### 5.2 Finance: Beta ($\beta$) in the CAPM
In finance, the "Beta" of a stock is the slope ($\beta_1$) of a linear regression where $x$ is the market return and $y$ is the stock's return. It measures how sensitive a stock is to market movements.

### 5.3 Medicine: Risk Factor Analysis
Doctors use logistic regression to calculate the probability of a patient having a heart attack based on predictors like blood pressure, age, cholesterol, and family history.

### 5.4 Software: Capacity Planning
Engineers use regression to predict when a database will run out of disk space. By regressing "Disk Usage" against "Time," they can estimate the date of exhaustion and provision hardware in advance.

---
**See Also:**
- [Statistics Fundamentals](StatisticsFundamentals) — The basics of data.
- [Statistical Inference](StatisticalInference) — Testing the significance of relationships.
- [Linear Algebra](LinearAlgebra) — The math behind solving the least squares equations.
- [Mathematics Hub](MathematicsHub) — Central index.
