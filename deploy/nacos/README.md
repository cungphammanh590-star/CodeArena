# Optional Nacos application.properties notes for CodeArena.
#
# Standalone mode is set via container env MODE=standalone in docker-compose.
# To customize, mount this directory or a properties file into the container:
#
#   volumes:
#     - ./deploy/nacos/application.properties:/home/nacos/conf/application.properties:ro
#
# Suggested overrides when auth / MySQL backend is needed later:
#   nacos.core.auth.enabled=false
#   spring.datasource.platform=mysql
#   db.url.0=jdbc:mysql://postgres:5432/nacos?...
#
# Console: http://localhost:8848/nacos  (default nacos/nacos when auth on)
