# aws-lambda-java-runtime

**A Java 11 Custom Runtime for AWS Lambda**

*Disclaimer - This project should be considered a POC and has not been tested or verified for production use. 
If you decided to run this on production systems you do so at your own risk.*

### Objective
This goal of this project is to build a Java 11 [AWS Custom Lambda Runtime](https://docs.aws.amazon.com/lambda/latest/dg/runtimes-custom.html) 
which will enable the use of new Java features not currently available in the official Java 1.8 Lambda Runtime. 

By implementing the [AWS Lambda Runtime API](https://docs.aws.amazon.com/lambda/latest/dg/runtimes-api.html)
 in Java 11, we're able to invoke handler functions compiled with Java Versions 9+. 

The intent of this project is to keep the runtime as lean as possible so we're not using any 3rd party
libraries and only the most basic JDK modules. Because of this goal you may notice REST API calls are 
handled using plain old Java UrlConnection and not Apache HttpClient as you'd typically expect. 

#####What's supported
* Request/Response Style Invocation
* Handler code deployed as either Jars or Zip

Using class path scanning we can match the loading process of the offical AWS Java Runtime to load Handler code as either
a Zip File or a Jar as documented by the official Lambda Docs:
https://docs.aws.amazon.com/lambda/latest/dg/create-deployment-pkg-zip-java.html

#####What's Not Supported
* Streaming invocation ie. Kinesis
* Json Marshalling
* Context support 

Since this is a basic POC, only Request/Response style lambda invocations are currently supported. Request/Response style 
invocation what you typically find in a serveries application. Alternatively Lambda can be invoked using streaming invocation
for example, when invoked by Kinesis, or other services. Streaming invocation is not currently supported in this runtime. 

Only Handlers which take a simple Object as their input parameter are currently supported. The official runtime supports a multitude 
of overloaded functions and does some POJO marshalling of Json. For the sake of time, this project only supports a 
simple object. Adding support for overloads, especially the Context parameter is fairly trivial and may be added later. 


## Building the Custom AWS Lambda Runtime

We're going to take advantage of the new Java Modules feature (Project Jigsaw) to package our 
runtime as a stand alone runtime image. This has the advantage of vastly reducing the footprint of our Runtime as 
well as simplifying deployment. It also means we're no longer required to have Java installed on the target
machine and can specify our own version.  

Before we start, just a quick note on packaging a stand alone Java im as it can be a little confusing. 
In order to create a stand alone image you need both the JDK for your OS and the 
JDK (or at least the jmods directory) for the target architecture of the system intended 
to run the image. Since our intended target is AWS Linux which Amazon uses for Lambda environments, we'll need the 
modules from the 64bit Linux JDK to link against. If we're compiling on a non-linux system ie. Mac or Windows, we'll
need both the JDK for our OS and the Linux JDK. Otherwise if you link to the wrong architecture you'll get an error when 
attempt to run the image.
 

## Building the Runtime


#####Compile the Runtime classes
Make sure you have Java 11 configured as your JDK, then run Gradle Build. Note that you'll also need Gradle 5.0 or 
later to build Java 11 project.

```
$gradlew build
```
##### Link the Runtime Image

As stated above, you'll need the JDK for Linux to link against. Download the Java 11 JDK for linux
and unzip it somewhere on your machine. Replace <path-to-linux-jdk> in the command below with the path
to the unzipped Linux JDK then run the linker. 

 ```
 $jlink --module-path ./build/libs:<path-to-linux-jdk>/jmods \
    --add-modules  com.ata.lambda \
    --output ./dist \
    --launcher bootstrap=com.ata.lambda/com.ata.aws.lambda.LambdaBootstrap \
    --compress 2 --no-header-files --no-man-pages --strip-debug
```

What we're doing above is using JLink to link our lambda module to the JDK modules it's dependent
upon to include only those dependencies in our image. 

Here's a breakdown of what we're doing in the command above. 

--module path: We first our module and also the module path of the Linux JDK to link to the JDK classes we're 
dependent upon in our code, ie. UrlConnection, Map, System etc. 

--output: Put everything in an output folder named ```dist``` in the cwd. 

--launcher: Create a launcher file called bootstrap which will be an file executable that invokes our main method directly. 
This means to run our application all we need to do is execute bootstrap like any other binary ie. ```$./bin/boostrap```. 
This removes the need to specify classpaths, modules etc. as you'd typically need to do when you run a jar file.

Everything else: The other parameters included are intended to cut down on the size of our deployment. Note we're stripping debug symbols from
our runtime binaries so if you wanted to debug the runtime you'd want to build without this setting. This should not affect
debug symbols on handler code uploaded to the lambda function which uses this runtime.


##### Create the Lambda Custom Runtime Entry Point

We're going to create a shell script to launch our application. AWS Lambda Custom Runtimes look for an executable file  
in the root directory named simply ```bootstrap```. This can be any executable file, for our case we're going to just use
a shell script to call our launcher that we created in the step above. 

**Create the bootstrap script**
```
$touch boostrap
```

**Add the following contents to bootstrap**
```$bash
#!/bin/sh
/opt/dist/bin/bootstrap
```

**Make the file executable**
```
$chmod +x bootstrap
```

Note that the path we're using in our shell script is /opt. AWS Lambda copies all of our files to the /opt directory when
we deploy as a layer. 

#### Deploy the Custom Runtime as a Layer

We should now have everything we need to deploy our Runtime. We could just include all of this with our handler function,
but it's much better to take advantage of the new Lambda Layers features so we can create a custom runtime layer
we can reuse with any lambda function. 

The deployment package needs to have ```bootstrap``` at the 
root level and we'll include our the folder containing our Java 11 Runtime Image we built with ```jlink``` above. Create a folder
which contains both of these artifacts so our hierarchy looks like this: 

```
- bootstrap
- dist
    - bin
        - boostrap
        - java
    -lib
        ...
    - ...
```

**Zip the above into a file named function.zip**

```
zip -r function.zip *
```

**Push the layer to AWS**
```
aws lambda publish-layer-version --layer-name Java-11 --zip-file fileb://function.zip
```

You should now have a new Layer called Java-11 which you can use in any Lambda function. 


## Using the Runtime

TODO



