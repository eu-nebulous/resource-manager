
ARG BUILDER_IMAGE=docker.io/eclipse-temurin:17.0.8.1_1-jre
ARG RUN_IMAGE=docker.io/eclipse-temurin:17.0.8.1_1-jre

# ----------------- Builder image -----------------
FROM $BUILDER_IMAGE as rd-builder
WORKDIR /app
COPY target/resource-discovery-*.jar .
RUN java -Djarmode=layertools -jar resource-discovery-*.jar extract

# -----------------   Runtime image   -----------------
FROM $RUN_IMAGE

# Setup environment
ENV BASEDIR /opt/resource-discovery
ENV RD_HOME ${BASEDIR}

# Install required and optional packages
RUN apt-get update && apt-get install -y \
    dumb-init \
    netcat \
    vim \
    iputils-ping \
    && rm -rf /var/lib/apt/lists/*

# Add RD user
ARG RD_USER=rd
RUN mkdir ${RD_HOME} ; \
    addgroup ${RD_USER} ; \
    adduser --home ${RD_HOME} --no-create-home --ingroup ${RD_USER} --disabled-password ${RD_USER} ; \
    chown ${RD_USER}:${RD_USER} ${RD_HOME}

# Set User and Workdir
USER ${RD_USER}
WORKDIR ${BASEDIR}

# Copy files from builder container
COPY --chown=${RD_USER}:${RD_USER} --from=rd-builder /app/dependencies          ${BASEDIR}
COPY --chown=${RD_USER}:${RD_USER} --from=rd-builder /app/spring-boot-loader    ${BASEDIR}
COPY --chown=${RD_USER}:${RD_USER} --from=rd-builder /app/snapshot-dependencies ${BASEDIR}
COPY --chown=${RD_USER}:${RD_USER} --from=rd-builder /app/application           ${BASEDIR}

# Copy entrypoint script
COPY --chown=${RD_USER}:${RD_USER} run.sh ${BASEDIR}

EXPOSE 8080

ENTRYPOINT ["dumb-init", "./run.sh"]