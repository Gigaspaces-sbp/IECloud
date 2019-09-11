FROM debian
COPY ./note.json /opt/scripts/note.json
COPY ./run.sh /opt/scripts/run.sh 
RUN chmod 755 /opt/scripts/run.sh
RUN apt-get update && apt-get install -y curl
ENTRYPOINT ["/bin/bash"]
