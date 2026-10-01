# The website online, as Render builds it (render.yaml; README, "Putting the site online").
# The database comes from the DATABASE_URL setting, the same one .env holds on a laptop.

FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY . .
RUN sh mvnw -B -q -DskipTests package && cp target/sla-web-*.jar app.jar

FROM eclipse-temurin:17-jre
RUN useradd --system --no-create-home sla
WORKDIR /app
COPY --from=build /src/app.jar app.jar
USER sla
# Open to the host's network, not only to this machine (application.properties).
ENV SERVER_ADDRESS=0.0.0.0
# The free plan has 512 MB; the rest is left for Java's own memory outside the heap.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "app.jar"]
