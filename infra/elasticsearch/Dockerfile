FROM docker.elastic.co/elasticsearch/elasticsearch:8.18.0
RUN curl -fsSL https://release.infinilabs.com/analysis-ik/stable/elasticsearch-analysis-ik-8.18.0.zip -o /tmp/ik.zip \
 && echo "3ff14b395289ad8e00e310b6514902512be43b63c7ac73cf9db92790e556b206  /tmp/ik.zip" | sha256sum -c - \
 && bin/elasticsearch-plugin install --batch file:///tmp/ik.zip \
 && rm /tmp/ik.zip
