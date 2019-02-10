# aws-lambda-java-runtime

### Custom Java 11 Runtime for AWS Lambda

*Disclaimer - This project should be considered a POC and has not been tested or verified for production use. 
If you decided to run this on production systems you do so at your own risk.*

### Objective
The goal of this project is to create an [AWS Lambda Custom Runtime](https://docs.aws.amazon.com/lambda/latest/dg/runtimes-custom.html) 
for Java to enable support of Java releases beyond the official AWS provided Runtimes.

This project will lay the foundation for future versions of Java to be supported
as they are released by implementing the [AWS Lambda Runtime API](https://docs.aws.amazon.com/lambda/latest/dg/runtimes-api.html) starting with the latest LTS release, Java 11.  

#### Primary Goals
* Lean Runtime
* Ease of Supporting New Java Versions
* Improved Performance of Java Lambda Functions

The aim of this project is to keep the run time as lean as possible. To aid in this goal, this project intends to leverage Java's new module system and linking process to create a stand alone Java runtime image containing only the base JDK dependencies required to implement the Lambda Runtime API. In addition, this project does not use any third party libraries to further reduce the footprint of the runtime. It is the hope of this project that leveraging a lean runtime and modular deployment can reduce the cost of the Lambda "cold starts". 


##### What's Supported
* Request/Response Style Invocation
* Function code deployments as either Jars or Zip

Using class path scanning we can match the loading process of the offical AWS Java Runtime to load Handler code as either
a Zip File or a Jar as documented by the official Lambda Docs:
https://docs.aws.amazon.com/lambda/latest/dg/create-deployment-pkg-zip-java.html

##### What's Not Currently Supported
* Streaming invocation ie. Kinesis
* Json Marshalling
* Context support 

Since this is a currently just a POC, only Request/Response style lambda invocations are currently supported. Request/Response style invocation what you typically find in a serverless application. Alternatively Lambda can be invoked using streaming invocation for example, when invoked by Kinesis, or other services. Streaming invocation is not currently supported in this runtime. This project may explore streaming invocation at a later time. 

Currently, only Handlers which take a simple Object as their input parameter are supported. The official AWS runtime supports a multitude of overloaded functions and does some POJO marshalling of Json using Jackson. See [Handler Input/Output Types](https://docs.aws.amazon.com/lambda/latest/dg/java-programming-model-req-resp.html) in the official Lambda documentation. 
To keep the scope simple for POC purposes and the remove the need for third party libraries ie. Jackson, this project only supports Handlers which accept a single object parameter. Adding support for overloads, especially the Context parameter is fairly trivial and will be added later. 

### Building this Runtime

The following sections describe how to build and assemble a Custom Runtime for AWS Lambda. We will assume a reasonablle familarity with AWS Lambda and Custom Runtimes. Please see the offical AWS Documentation for [AWS Lambda](https://docs.aws.amazon.com/lambda/latest/dg/welcome.html) and [AWS Lambda Runtimes](https://docs.aws.amazon.com/lambda/latest/dg/lambda-runtimes.html) for more details. 

As mentioned in the Objective, this project will take advantage of the new Java Modules feature (Project Jigsaw) to package the runtime as a stand alone Java image. This has the advantage of vastly reducing the Runtime's footprint as 
well as simplifying the deployment process. As a result of the new deployment system, Java is no longer required to be pre-installed on the execution environment. The deployment will be compeltley self contained allowing for any version of Java to be deployed on Lambda.

Before we start, just a quick note on building stand alone Java images as there are some gotchas. 
Since we will be basically building an executable, we need to be able to link our modules to the target environment's JDK rather than the JDK of our build environment. Since our intended target is AWS Linux which Amazon uses for Lambda environments, we'll need the modules from the 64bit Linux JDK to link against. If we're compiling on a non-linux system ie. Mac or Windows, we'll need both the JDK for our development environment and the Linux JDK. Otherwise if you link to the wrong architecture you'll get an error when attempt to run the image.
 

#### Prerequisites 

Make sure you have the following installed on your build machine before getting started. 
* OpenJDK 11 for your OS
* OpenJDK 11 for Linux (See note above)
* AWS CLI

##### Compile the Runtime Classes

Run the Gradle Build included with this project. NOTE that Gradle 5.0 or greater is required to build Java 11+ projects. 
```
$ ./gradlew build
```
##### Linking the Runtime Image

As stated above, you'll need the JDK for Linux to link our module against. If you haven't already, download the Java 11 JDK for linux and unzip it somewhere on your machine. Replace ```<path-to-linux-jdk>``` in the command below with the path
to the unzipped Linux JDK then run the linker. Make sure you're using the same Major/Minor versions of both your build JDK and the target JDK to eliminate potential incompatibilities. 

 ```
 $ jlink --module-path ./build/libs:<path-to-linux-jdk>/jmods \
    --add-modules  com.ata.lambda \
    --output ./dist \
    --launcher bootstrap=com.ata.lambda/com.ata.aws.lambda.LambdaBootstrap \
    --compress 2 --no-header-files --no-man-pages --strip-debug
```

What we're doing here is using the ```jlink``` tool included with the latest JDKs to link our module to the JDK modules 
it's dependent upon to include only those dependencies in our image. This creates a small stand alone distribution which can 
be run on any machine without requiring Java to be installed.  

Here's a breakdown of the ```jlink``` parameters above:

**--module-path:** Links our module and the Linux JDK Runtime modules to link the built in Java classes we're 
dependent upon in our code, ie. UrlConnection, Map, System etc.

**--add-modules:** Add our module as defined in ```module-info.java```

**--output:** Put everything in an output folder named ```dist``` relative to the cwd. This folder will contain
our stand alone Java Runtime with our Lambda Module embedded. 

**--launcher:** Create a launcher file called "bootstrap" which will be an file executable that invokes our main method directly. This means to run our application all we need to do is execute bootstrap like any other binary ie. ```$ ./bin/bootstrap```. This removes the need to specify classpaths, modules etc. as you'd typically need to do when you run a jar file.

**Everything else:** The other parameters included are intended to cut down on the size of our deployment. Note we're stripping debug symbols from
our runtime binaries so if you wanted to debug the runtime you'd want to build without this setting. This will not affect
debug symbols on Handler code uploaded to the lambda function which uses this runtime.


#### Create the Lambda Custom Runtime Entry Point

AWS Lambda Custom Runtimes require an executable file in the root directory named simply ```bootstrap```. This can be any executable file, for our case we're going to just use
a shell script to call our launcher that we created in the step above. This script will do nothing more than invoke our Java Runtime from the dist folder.

##### Create the bootstrap script
```
$ touch boostrap
```
##### Call our Java Runtime from Bash
Add the following commands to the ```bootstrap```
```$bash
#!/bin/sh
/opt/dist/bin/bootstrap
```

Note that the path we're using in our shell script is ```/opt```. When you create a Lambda Layer, as we'll do shortly, AWS Lambda copies all the runtime files to the ```/opt``` directory. This directory is efficvely the home directory for our custom runtime. 

##### Make bootstrap executable
```
$ chmod +x bootstrap 
```

#### Deploying the Custom Runtime as a Layer

We should now have everything we need to deploy a Custom Runtime. We could just package all of this with a Handler 
function and call it a day, but a better practice is to take advantage of Layers. When we create a Custom Runtime as 
a Layer it can be reused with any Lambda function.  

The deployment package needs to have ```bootstrap``` at the root level and we'll include the folder 
containing our Java 11 Runtime Image we built with ```jlink``` above. 

Create a folder which contains both of these artifacts, ```bootstrap``` and ```dist```, so we can package them
as a Layer. The deployment hierarchy should like this: 

```
- bootstrap
- dist
    - bin
        - boostrap
        - java
        - keytool
    -conf
    -legal
    -lib
```

You'll need all of these files so make sure you include the full ```dist``` folder and all of its sub-folders. 

##### Create a deployment package

In the root of the folder containing our ```bootstrap``` and ```dist``` files, create a zip archive containing the artifacts. 

```
$ zip -r function.zip *
```

##### Publish the Layer 

Using the AWS CLI, push the Layer to Lambda. 
```
$ aws lambda publish-layer-version --layer-name Java-11 --zip-file fileb://function.zip
```

You should now have a new Layer called Java-11 which you can use in any Lambda function. 


## Using the Runtime

Now that the Layer is created we can create a new Lambda function using the Java 11 Runtime and build a
Handler Function that uses Java 11 features. 

The rest of this guide assumes you are already familiar with building Lambda Functions in Java, if not please see the [Working in Java](https://docs.aws.amazon.com/lambda/latest/dg/java-programming-model.html)
section of the official AWS Lambda documentation.

##### Sample Java Handler using Var
To prove that this is all working, create a sample Lambda Function using the Java 'var' keyword added in Java 10. This code would not execute on the official Java 8 Lambda Runtime provided by Amazon, but will work on our Lambda.  

```java
public class SampleLambdaHandler {

    public String myHandler(Object input) {

        var java11Var = "Hello var keyword";
        System.out.println("Logging a Java 'var': " + java11Var);

        return "I'm a Java handler";
    }
}

```

Compile and package this into either a ```jar``` or a  ```zip``` file and upload it to a new lambda function. 
Assuming you named your deployment ```handler.zip```, you could create a new Lambda Function as follows:

##### Create a New Lambda Function
```
$ aws lambda create-function --function-name testJavaHandler \
--zip-file fileb://handler.zip --handler SampleLambdaHandler::myHandler --runtime provided \
--role arn:aws:iam::<your-account>:role/lambda-role

```
NOTE: You'll need to replace the IAM role above with the ARN of your Lambda IAM role.

##### Attach the Java 11 Runtime Layer
You'll notice we used ```--runtime provided``` in the command above to tell AWS that we're using a custom runtime. But since we're not packaging our ```bootstrap``` file with our deployment package we'll need to attach the Layer we created earlier which contains our custom runtime to this Lambda Function

For this step you'll need the arn of your Java 11 Custom Runtime Layer and its version nunmber. You should have seen that in the output of the command we used to create it earlier or you can run the following command to list your available layers. You will be able to find the ARN in the response under the field ```LayerVersionArn```

```
$ aws lambda list-layers
```
The ARN for our lambda should look something like this

```arn:aws:lambda:us-east-1:<account-id>:layer:Java-11:1```

Where account-id is your AWS accound and the number on the end is the version of the Layer. Evertime you update or publish a layer that version number will increase. 

Now that we have the ARN for our Layer we can update our Lambda function
```
$ aws lambda update-function-configuration --function-name testJavaHandler --layers arn:aws:lambda:us-east-1:<account-id>:layer:Java-11:1
```

Replace the ARN in the --layers parameter in the command above with the ARN of your Java 11 Layer. 

##### Execute the Lambda. 

Now let's test the new function and our custom runtime. We can do this from the command line with the 
following command. I've added a little extra command line magic to display the log messages on the console. 
"response.txt" will hold the results of the invocation. 

```
$ aws lambda invoke --function-name testJavaHandler  --payload '{"message":"Hello World"}' --log-type Tail response.txt | grep "LogResult"| awk -F'"' '{print $4}' | base64 --decode
START RequestId: e5f273a6-e2bf-44a6-bacd-c281dfb14061 Version: $LATEST
Logging a Java 'var': Hello var keyword
END RequestId: e5f273a6-e2bf-44a6-bacd-c281dfb14061
REPORT RequestId: e5f273a6-e2bf-44a6-bacd-c281dfb14061	Init Duration: 427.65 ms	Duration: 132.56 ms	Billed Duration: 600 ms 	Memory Size: 512 MB	Max Memory Used: 67 MB	
```

###### In response.txt

```
I'm a Java handler
```

It works! Now you can build Lambda Functions using Java 11. Enjoy!



