FROM node:18-alpine AS builder
WORKDIR /app

# Copy dependency definitions
COPY package*.json ./

# Clean install to regenerate proper bin links for ESM
RUN npm ci || npm install

# Copy the rest of the frontend files
COPY . .

# Backend URL baked into the bundle at build time. Override with e.g.
#   docker compose build --build-arg VITE_API_BASE_URL=http://192.168.x.x:8080 frontend
ARG VITE_API_BASE_URL=http://127.0.0.1:8080
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL

# Run the build
RUN npm run build

# Production stage
FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]