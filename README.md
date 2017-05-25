# git-to-s3
This is a AWS Lambda function that will be triggered by AWS Codecommit push event. It will load files from codecommit and synchronize them to s3 bucket. An notification email will be sent after it finish all the file uploads.

# Use case
You can use it as either a configuration managment or a s3 hosted static web page auto deployment tool. 

# HowTo
Give your lambda function the following permissions:
1. "codecommit:BatchGetRepositories",
   "codecommit:Get*",
   "codecommit:GitPull",
   "codecommit:List*"
2. "ses:SendEmail",
   "ses:SendRawEmail"
3. "s3:Put*"

edit ./deploy.sh by replacing ${LAMBDA_REGION} and ${FUNCTION-NAME} and run this file. The script will do the maven build and upload jar file to lambda

# environment variables: 
s3Bucket -- target bucket  

s3BasePath -- optional, the base folder in target bucket

s3Region -- bucket region

httpUrl -- http clone url of codecommit repo

accessKey -- KMS encrypted username of codecommit repo

secretKey -- KMS encrypted password of codecommit repo

sesRegion -- region of ses

fromEmail -- sender's email

toEmail -- receivers' emails. seperated by ","
