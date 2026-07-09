# Kingdom of Lumbridge — Discord bot worker
FROM node:20-alpine
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# Runs via tsx (no build step). Long-lived gateway connection.
CMD ["npm", "run", "start"]
