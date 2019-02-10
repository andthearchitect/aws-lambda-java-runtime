# aws-lambda-java-runtime

### Custom Java 11 Runtime for AWS Lambda

*Disclaimer - This project should be considered a POC and has not been tested or verified for production use. 
If you decided to run this on production systems you do so at your own risk.*

### Objective
The goal of this project is to create an [AWS Lambda Custom Runtime](https://docs.aws.amazon.com/lambda/latest/dg/runtimes-custom.html) 
for Java to enable support of Java releases beyond the official AWS provided Runtimes.

This project will lay the foundation for future versions of Java to be supported
as they are released by implementing the [AWS Lambda Runtime API](https://docs.aws.amazon.com/lambda/latest/dg/runtimes-api.html)
 starting with the latest LTS release, Java 11.  

By design, this project makes use of no 3rd party libraries with the intent of keeping the runtime as lean as possible. To aid in this goal, 
this project intends to leverage Java's new module system and linking process to create a stand alone Java runtime image containing only 
the base JDK features needed to implement the Lambda Runtime API. In addition, it is the aim of this project to reduce the cost of
Lambda "cold starts" by leveraging the module system in addition to keeping the runtime as lean as possible. 


##### What's supported
* Request/Response Style Invocation
* Handler code deployed as either Jars or Zip

Using class path scanning we can match the loading process of the offical AWS Java Runtime to load Handler code as either
a Zip File or a Jar as documented by the official Lambda Docs:
https://docs.aws.amazon.com/lambda/latest/dg/create-deployment-pkg-zip-java.html

##### What's Not Supported
* Streaming invocation ie. Kinesis
* Json Marshalling
* Context support 

Since this is a currently just a POC, only Request/Response style lambda invocations are currently supported. Request/Response style 
invocation what you typically find in a serveries application. Alternatively Lambda can be invoked using streaming invocation
for example, when invoked by Kinesis, or other services. Streaming invocation is not currently supported in this runtime. 

Only Handlers which take a simple Object as their input parameter are currently supported. The official runtime supports a multitude 
of overloaded functions and does some POJO marshalling of Json. For the sake of time, this project only supports a 
simple object. Adding support for overloads, especially the Context parameter is fairly trivial and may be added later. 

### Building the Runtime

We're going to take advantage of the new Java Modules feature (Project Jigsaw) to package our 
runtime as a stand alone Java image. This has the advantage of vastly reducing the footprint of our Runtime as 
well as simplifying deployment. It also means we're no longer required to have Java installed on the target
machine and can specify our own version.  

Before we start, just a quick note on packaging a stand alone Java im as it can be a little confusing. 
In order to create a stand alone image you need both the JDK for your OS and the 
JDK (or at least the jmods directory) for the target architecture of the system intended 
to run the image. Since our intended target is AWS Linux which Amazon uses for Lambda environments, we'll need the 
modules from the 64bit Linux JDK to link against. If we're compiling on a non-linux system ie. Mac or Windows, we'll
need both the JDK for our OS and the Linux JDK. Otherwise if you link to the wrong architecture you'll get an error when 
attempt to run the image.
 

#### Prerequisites 

Make sure you have the following installed on your build machine before getting started. 
* OpenJDK 11 for your OS
* OpenJDK 11 for Linux (See note above)
* Gradle 5+ (Required to build Java 11 Projects)
* AWS CLI

##### Compile the Runtime Classes

```
$ ./gradlew build
```
##### Linking the Runtime Image

As stated above, you'll need the JDK for Linux to link our module against. Download the Java 11 JDK for linux
and unzip it somewhere on your machine. Replace <path-to-linux-jdk> in the command below with the path
to the unzipped Linux JDK then run the linker.

 ```
 $ jlink --module-path ./build/libs:<path-to-linux-jdk>/jmods \
    --add-modules  com.ata.lambda \
    --output ./dist \
    --launcher bootstrap=com.ata.lambda/com.ata.aws.lambda.LambdaBootstrap \
    --compress 2 --no-header-files --no-man-pages --strip-debug
```

What we're doing above is using the ```jlink``` tool included with the latest JDKs to link our module to the JDK modules 
it's dependent upon to include only those dependencies in our image. This creates a stand alone distribution which can 
be run on any machine without requiring Java to be installed.  

Here's a breakdown of what we're doing in the command above. 

**--module-path:** Links our module and the Linux JDK Runtime modules to link the built in Java classes we're 
dependent upon in our code, ie. UrlConnection, Map, System etc.

**--output:** Put everything in an output folder named ```dist``` relative to the cwd. This folder will contain
our stand alone Java Runtime with our Lambda Module embedded. 

**--launcher:** Create a launcher file called "bootstrap" which will be an file executable that invokes our main method directly. 
This means to run our application all we need to do is execute bootstrap like any other binary ie. ```$./bin/boostrap```. 
This removes the need to specify classpaths, modules etc. as you'd typically need to do when you run a jar file.

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
Add the following commands to ```bootstrap```
```$bash
#!/bin/sh
/opt/dist/bin/bootstrap
```

Note that the path we're using in our shell script is ```/opt```. When you create a Lambda Layer, as we'll do shortly, AWS Lambda 
copies all the runtime files to the /opt directory.  

##### Make bootstrap executable
```
$ chmod +x bootstrap 
```

#### Deploying the Custom Runtime as a Layer

We should now have everything we need to deploy a Custom Runtime. We could just package all of this with a Handler 
function and call it a day, but a better practice is to take advantage Layers. When we create a Custom Runtime as 
a Layer it can be reused with any Lambda function.  

The deployment package needs to have ```bootstrap``` at the root level and we'll include our the folder 
containing our Java 11 Runtime Image we built with ```jlink``` above. 

Create a folder which contains both of these artifacts, ```bootstrap``` and ```dist``` so we can package them
as a Layer.

The deployment hierarchy should like this: 

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

Create a zip containing our runtime files. 

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
To prove that this is all working let's create a sample Lambda Function using hte new Java 'var' keyword
which was added in Java 10. This could would not run on the official Java 8 Lambda Runtime provided by Amazon, but 
will work on our Lambda.  

```java
public class SampleLambdaHandler {

    public String myHandler(Object input) {

        var java11Var = " Hello var keyword";
        System.out.println("Logging a Java 'var'" + java11Var);

        return "I'm a Java handler";
    }
}

```

Package this into either a ```jar``` or a  ```zip``` file and upload it to a new lambda function. 
Assuming you named your deployment ```handler.zip```, you could create a new Lambda Function as follows:

```
$ aws lambda create-function --function-name testJavaHandler \
--zip-file fileb://handler.zip --handler SampleLambdaHandler::myHandler--runtime provided \
--role arn:aws:iam::<your-account>:role/lambda-role

```

##### Execute the Lambda. 

Now let's test the new function and our custom runtime. We can do this from the command line with the 
following command. Note: I've added a little extra to display the log messages on the console and 
"response.txt" will hold the results of the invocation. 


```
$ aws lambda invoke --function-name testJavaHandler  --payload '{"message":"Hello World"}' --log-type Tail response.txt | grep "LogResult"| awk -F'"' '{print $4}' | base64 --decode
START RequestId: e5f273a6-e2bf-44a6-bacd-c281dfb14061 Version: $LATEST
Logging a Java 'var' Hello var keyword
END RequestId: e5f273a6-e2bf-44a6-bacd-c281dfb14061
REPORT RequestId: e5f273a6-e2bf-44a6-bacd-c281dfb14061	Init Duration: 427.65 ms	Duration: 132.56 ms	Billed Duration: 600 ms 	Memory Size: 512 MB	Max Memory Used: 67 MB	
```

###### In response.txt

```
I'm a Java handler
```






