---
canonical_id: 01KQ0P44XQ9WWFHYKFSFVMCEYF
title: Time Series Forecasting
type: article
cluster: machine-learning
status: active
date: '2026-05-15'
tags:
- time-series
- forecasting
- xgboost
- arima
- lstm
summary: Technical analysis of time series forecasting, covering signal decomposition, classical statistical models, and a concrete XGBoost implementation with lag features.
related:
- MachineLearning
- FeatureEngineering
- DeepLearningFundamentals
- TransformerArchitecture
hubs:
- MachineLearningHub
auto-generated: false
---
# Time Series Forecasting

Time series forecasting is the task of predicting future values based on historical temporal patterns. It requires modeling three core components: **Trend** (long-term direction), **Seasonality** (repeating cycles), and **Residuals** (noise).

## 1. Core Methodologies
- **Classical Statistical Models (ARIMA, ETS)**: Assume the series is stationary (constant mean/variance) or can be made stationary through differencing. Strong for short-term, linear trends.
- **Prophet (Facebook)**: A decomposable additive model that handles holidays, outliers, and missing data robustly.
- **Deep Learning (LSTM, GRU)**: Uses recurrent gates to maintain "memory" of long-term dependencies.
- **Transformers**: Uses self-attention to weight the importance of specific past time steps, regardless of their distance.

## 2. Feature Engineering for Time Series
When using machine learning models (like XGBoost), time must be converted into features:
- **Lag Features**: The value at $t-n$ (e.g., yesterday's price).
- **Window Features**: Rolling mean or standard deviation over a 7-day or 30-day window.
- **Date/Time Parts**: Hour of day, day of week, month, and binary flags for holidays.

## 3. Concrete Example: XGBoost with Lag Features
Using a Gradient Boosting Machine (GBM) for time series allows capturing non-linear relationships that ARIMA misses.

```python
import pandas as pd
import xgboost as xgb
from sklearn.metrics import mean_squared_error

# 1. Prepare Data with Lag Features
df = pd.read_csv("sales_data.csv", parse_dates=['date'])
df['lag_1'] = df['sales'].shift(1)
df['lag_7'] = df['sales'].shift(7)
df['rolling_mean_7'] = df['sales'].rolling(window=7).mean()

# Add temporal features
df['day_of_week'] = df['date'].dt.dayofweek
df['month'] = df['date'].dt.month

# 2. Split (Ensuring no look-ahead bias)
train = df[df['date'] < '2024-01-01']
test = df[df['date'] >= '2024-01-01']

X_train = train.drop(['date', 'sales'], axis=1)
y_train = train['sales']
X_test = test.drop(['date', 'sales'], axis=1)
y_test = test['sales']

# 3. Model Training
model = xgb.XGBRegressor(n_estimators=1000, learning_rate=0.05, max_depth=5)
model.fit(X_train, y_train, eval_set=[(X_test, y_test)], verbose=False)

# 4. Predict
predictions = model.predict(X_test)
```

## 4. Evaluation Metrics
- **MAE (Mean Absolute Error)**: Average error in absolute terms.
- **RMSE (Root Mean Squared Error)**: Penalizes large errors more heavily.
- **MAPE (Mean Absolute Percentage Error)**: Useful for comparing error across series with different scales.

## 5. Handling Concept Drift
Real-world time series are non-stationary.
- **Walk-Forward Validation**: Re-train the model as new data arrives to capture evolving patterns.
- **Change Point Detection**: Use algorithms (e.g., PELT) to detect structural breaks in the trend and trigger a model reset.

## Summary of Technical implementation added
- Stripped verbose philosophical introduction.
- Defined **Trend, Seasonality, and Residuals** as the core decomposition targets.
- Provided a complete **Python/XGBoost example** with lag and rolling window features.
- Explained the **Walk-Forward Validation** strategy to handle non-stationarity.
- Listed essential metrics (MAE, RMSE, MAPE).
