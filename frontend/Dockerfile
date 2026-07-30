FROM node:18-alpine AS builder
WORKDIR /app

# Copy dependency definitions
COPY package*.json ./

# Clean install to regenerate proper bin links for ESM
RUN npm ci || npm install

# Copy the rest of the frontend files
COPY . .

# Run the build
RUN npm run build

# Production stage
FROM nginx:alpine
COPY --from=builder /app/dist /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]