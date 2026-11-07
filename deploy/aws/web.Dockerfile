FROM nginx:stable-alpine

COPY deploy/aws/nginx.conf /etc/nginx/conf.d/default.conf

COPY frontend/*.html /usr/share/nginx/html/
COPY frontend/css /usr/share/nginx/html/css
COPY frontend/js /usr/share/nginx/html/js
COPY frontend/images /usr/share/nginx/html/images
COPY frontend/manifest.webmanifest /usr/share/nginx/html/

# Send browser API requests to the same hostname as the website.
RUN sed -i 's|name="api-base-url" content="http://localhost:8080"|name="api-base-url" content=""|g' /usr/share/nginx/html/*.html