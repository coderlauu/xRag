import { readFileSync } from "node:fs";

const compose = readFileSync(
  new URL("../deploy/compose/stack.compose.yml", import.meta.url),
  "utf8",
);
const githubDeploy = readFileSync(
  new URL("./gh-deploy-ssh.sh", import.meta.url),
  "utf8",
);
const remoteDeploy = readFileSync(
  new URL("../deploy/scripts/remote-deploy.sh", import.meta.url),
  "utf8",
);

const servicesSection = compose.match(/^services:\n([\s\S]*?)^volumes:/m)?.[1] ?? "";
const composeServices = new Set(
  [...servicesSection.matchAll(/^  ([a-z][a-z0-9-]*):$/gm)].map(
    (match) => match[1],
  ),
);
const composeImages = new Set(
  [...servicesSection.matchAll(/^\s+image:\s+\$\{([A-Z][A-Z0-9_]*)\}$/gm)].map(
    (match) => match[1],
  ),
);
const requiredImages = new Set(
  [...githubDeploy.matchAll(/^: "\$\{([A-Z][A-Z0-9_]*_IMAGE):\?/gm)].map(
    (match) => match[1],
  ),
);

const referencedServices = new Set();
for (const line of remoteDeploy.split("\n")) {
  const command = line.match(/^compose_run (run|up)\s+(.+)$/);
  if (command) {
    const services = command[2]
      .split(/\s+/)
      .filter((token) => !token.startsWith("-") && !token.startsWith("<"));
    for (const service of command[1] === "run" ? services.slice(0, 1) : services) {
      referencedServices.add(service);
    }
  }

  const serviceCheck = line.match(
    /^(?:wait_for_service_stable|verify_service_image) ([a-z][a-z0-9-]*)\b/,
  );
  if (serviceCheck) {
    referencedServices.add(serviceCheck[1]);
  }
}

const errors = [];
for (const image of requiredImages) {
  if (!composeImages.has(image)) {
    errors.push(`gh-deploy-ssh.sh requires unused image variable: ${image}`);
  }
}
for (const image of composeImages) {
  if (!requiredImages.has(image)) {
    errors.push(`gh-deploy-ssh.sh does not require compose image: ${image}`);
  }
}
for (const service of referencedServices) {
  if (!composeServices.has(service)) {
    errors.push(`remote-deploy.sh references missing compose service: ${service}`);
  }
}

if (errors.length > 0) {
  console.error("Deployment contract check failed:");
  for (const error of errors) {
    console.error(`- ${error}`);
  }
  process.exit(1);
}

console.log("Deployment contract check passed.");
