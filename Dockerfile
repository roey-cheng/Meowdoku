FROM eclipse-temurin:17-jdk AS builder

WORKDIR /app
COPY . .
RUN javac -encoding UTF-8 -d /app/build \
    BrowserPlayer.java Cell.java GameBoard.java HumanPlayer.java Main.java \
    MeowdokuGame.java Player.java Position.java RandomPlayer.java SequentialPlayer.java \
    WebGameTest.java WebMain.java WebServerSmokeTest.java WebServerTest.java

FROM eclipse-temurin:17-jre

WORKDIR /app
COPY --from=builder /app/build /app
COPY --from=builder /app/web /app/web
EXPOSE 8080

CMD ["java", "WebMain"]
