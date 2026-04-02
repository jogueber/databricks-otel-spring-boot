# Databricks notebook source
# Example values for the placeholders below:
# MLFLOW_TRACING_SQL_WAREHOUSE_ID: "abc123def456" (found in SQL warehouse URL)
# experiment_name: "/Users/user@company.com/traces"
# catalog_name: "main" or "my_catalog"
# schema_name: "mlflow_traces" or "production_traces"

import os
import mlflow
from mlflow.exceptions import MlflowException
from mlflow.entities import UCSchemaLocation
from mlflow.tracing.enablement import set_experiment_trace_location

mlflow.set_tracking_uri("databricks")

# Specify the ID of a SQL warehouse you have access to.
os.environ["MLFLOW_TRACING_SQL_WAREHOUSE_ID"] = "<your-warehouse-id>"
# Specify the name of the MLflow Experiment to use for viewing traces in the UI.
experiment_name = "/Users/<your-email>/ai-tracing-demo"
# Specify the name of the Catalog to use for storing traces.
catalog_name = "<your-catalog>"
# Specify the name of the Schema to use for storing traces.
schema_name = "<your-schema>"

experiment_id = mlflow.create_experiment(name=experiment_name)
print(f"Experiment ID: {experiment_id}")

# To link an experiment to a trace location
result = set_experiment_trace_location(
    location=UCSchemaLocation(catalog_name=catalog_name, schema_name=schema_name),
    experiment_id=experiment_id,
)
print(result.full_otel_spans_table_name)