FROM node:22-alpine AS builder

ENV PNPM_HOME=/pnpm
ENV PATH="${PNPM_HOME}:${PATH}"

RUN corepack enable

WORKDIR /app

COPY . .

RUN pnpm install --frozen-lockfile
RUN pnpm --filter @xrag/worker build

FROM node:22-alpine AS runner

ENV NODE_ENV=production

RUN apk add --no-cache \
  poppler-utils \
  tesseract-ocr \
  tesseract-ocr-data-eng \
  tesseract-ocr-data-chi_sim

WORKDIR /app

COPY --from=builder /app /app

CMD ["node", "apps/worker/dist/main.js"]
