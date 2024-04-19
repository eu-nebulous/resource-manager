#!/bin/bash

# Define MongoDB connection details
MONGODB_HOST="localhost"
MONGODB_PORT="27017"

# Function to check if MongoDB is ready
wait_for_mongodb() {
    echo "Waiting for MongoDB to be ready..."
    until nc -z $MONGODB_HOST $MONGODB_PORT
    do
        sleep 1
    done
    echo "MongoDB is up and running!"
}

# Call the function to wait for MongoDB
wait_for_mongodb