FROM gcr.io/distroless/java21-debian12
COPY build/libs/*.jar app.jar
CMD [ "app.jar" ]
