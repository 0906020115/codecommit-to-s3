mvn clean install  -Dmaven.test.skip
aws lambda --region ${LAMBDA_REGION} update-function-code --function-name ${FUNCTION-NAME} --zip-file fileb://./target/codecommit-to-s3-1.0.jar
