# Building from the source code

## Minimum requirements

The project can be built using at least Java 17 and Maven 3.8.5. To check your Java and Maven versions, execute

```shell
java --version
```

and 

```shell
mvn -v
```

If the versions do not fit the requirements, update them accordingly.

## Getting the source code 

Use  

```shell
git clone https://github.com/sdeigm/uni-meter.git
```

to clone the `uni-meter` GitHub repository to your local development machine. Afterward change into the created 
directory and execute

```shell
mvn install
```

That will create a `uni-meter-<version>.tgz` archive in the `target` directory. This archive can then be installed as
described in [Installation on a physical server](BareMetal.md)
