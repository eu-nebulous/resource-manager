
# ----------------- Builder image -----------------
FROM docker.io/library/maven:3.9.6-eclipse-temurin-21 as rd-builder
ENV BASEDIR /app
WORKDIR ${BASEDIR}
COPY src        ${BASEDIR}/src
COPY pom.xml    ${BASEDIR}/
RUN mvn -f ${BASEDIR}/pom.xml -DskipTests clean install && \
    java -Djarmode=layertools -jar ${BASEDIR}/target/resource-discovery-*.jar extract

# -----------------   Runtime image   -----------------
FROM docker.io/library/eclipse-temurin:21.0.1_12-jre

# Setup environment
ENV BASEDIR /opt/resource-discovery
ENV RD_HOME ${BASEDIR}

# Install required and optional packages
RUN wget  --progress=dot:giga -O /usr/local/bin/dumb-init \
          https://github.com/Yelp/dumb-init/releases/download/v1.2.5/dumb-init_1.2.5_x86_64 && \
    chmod +x /usr/local/bin/dumb-init

# Add RD user
ARG RD_USER=root
#ARG RD_USER=rd
#RUN mkdir ${RD_HOME} && \
#    addgroup ${RD_USER} && \
#    adduser --home ${RD_HOME} --no-create-home --ingroup ${RD_USER} --disabled-password ${RD_USER} && \
#    chown ${RD_USER}:${RD_USER} ${RD_HOME}

RUN apt-get update \
    && apt-get install --no-install-recommends -y \
        netcat=1.218-4ubuntu1 \
        iputils-ping=3:20211215-1 \
        telnet=0.17-44build1 \
        vim=2:8.2.3995-1ubuntu2.16 \
        net-tools=1.60+git20181103.0eebece-1ubuntu5 \
    && rm -rf /var/lib/apt/lists/*

# Set User and Workdir
USER ${RD_USER}
WORKDIR ${BASEDIR}

# Copy files from builder container
COPY --chown=${RD_USER}:${RD_USER} --from=rd-builder /app/dependencies          ${BASEDIR}
COPY --chown=${RD_USER}:${RD_USER} --from=rd-builder /app/spring-boot-loader    ${BASEDIR}
COPY --chown=${RD_USER}:${RD_USER} --from=rd-builder /app/snapshot-dependencies ${BASEDIR}
COPY --chown=${RD_USER}:${RD_USER} --from=rd-builder /app/application           ${BASEDIR}
#COPY --chown=${RD_USER}:${RD_USER} --from=rd-builder /app/run.sh                ${BASEDIR}

COPY --chown=${RD_USER}:${RD_USER} run.sh                                       ${BASEDIR}
COPY --chown=${RD_USER}:${RD_USER} wait_for_mongodb.sh                          ${BASEDIR}
RUN chmod +x run.sh wait_for_mongodb.sh

EXPOSE 8080

ENTRYPOINT ["dumb-init", "./run.sh"]