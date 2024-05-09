#!/bin/bash

# Define MongoDB connection details
if [ -z "$MONGODB_HOST" ]; then MONGODB_HOST="localhost"; fi
if [ -z "$MONGODB_PORT" ]; then MONGODB_PORT="27017"; fi

# Function to check if MongoDB is ready
wait_for_mongodb() {
    echo "Waiting for MongoDB ($MONGODB_HOST:$MONGODB_PORT) to be ready..."
    until nc -z $MONGODB_HOST $MONGODB_PORT
    do
        sleep 1
    done
    echo "MongoDB is up and running!"
}

# Call the function to wait for MongoDB
wait_for_mongodb