FROM java:8


ADD data-feeder-1.0.jar data-feeder.jar
ADD data.csv data.csv

ENTRYPOINT ["java", "-jar", "data-feeder.jar", "--spring.profiles.active=prod"]
