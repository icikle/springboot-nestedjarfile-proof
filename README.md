# Project to replicate contention in NestedJarFile reported in gh51463

This small project attempts to replicate the same thread blockage pattern reported in gh 51463
using a small app utilizing similar patterns to what we see in our own application which also sees 
the issue.

* Uses PropertiesLauncher
* Uses combination of fat jar and additional project libs
* Uses Executors.newVirtualThreadPerTaskExecutor - both our issue and original reporter point to virtual thread usage
* Secondary execution utilising RestClient.build which also requires access to NestedJarFile in this context ( note this just builds the client and doesn't use it)
* Project simulates boot up multiple tenants on start up parsing through configuration files from both fat jar and jars in the lib

## Build and run

Build with spring boot 4.1.1
```
./gradlew.sh build
``

Build with a snapshot of spring-boot-loader.  Used to test the app against possible fixes

```
./gradlew build -PloaderVersion=4.1.2-SNAPSHOT
```

After build set up the directory structure for the lib director

```
sh make_lib_dir.sh
```

This just takes the jars and puts them in one lib director for launching.

```
sh run.sh
```

Runs the app with PropertiesLauncher and a max of 3 CPUs

## Thread dumps

There are a number of thread dumps from the running app in the thread-dumps folder. Most don't have much context but do show the blocking
on hasEntry.

The repro3-a.json, repro3-b.json, repro3-classic-a.txt and repro3-classic-b.txt show the same process a few seconds appart in both json and "classic" thread dumps. These show that the application is indeed hung as there is not movement between the a and b dumps.  Also this shows that the text version does not
show this as being detected as a deadlock


## Possible future investigation

* Switch to use a non virtual thread executor and verify both if the issue is hit and how the thread dumps report the issue with classic threads.
