/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  // The web app and the Kotlin game server both talk to the same MongoDB.
  // Server-only secrets are read from process.env at runtime (see .env.example).
  experimental: {
    serverComponentsExternalPackages: ["mongodb", "bcryptjs"],
  },
};

export default nextConfig;
