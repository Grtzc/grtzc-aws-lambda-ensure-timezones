# Ensure Timezones Update lambda function

This project is an AWS lambda function that will be executed daily, and checks if a new version of timezones has been released or not.

It downloads and parses it and will replace current timezones in DynamoDB
