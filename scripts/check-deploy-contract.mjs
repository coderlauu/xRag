import { readdirSync, readFileSync } from "node:fs";

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
const applicationProperties = readFileSync(
  new URL("../backend/src/main/resources/application.properties", import.meta.url),
  "utf8",
);
const deployReadme = readFileSync(
  new URL("../deploy/README.md", import.meta.url),
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

/*
 * 问答链路的可调配置必须在 deploy/README 有登记条目。
 *
 * 只覆盖 QA_ 前缀，不是偷懒：application.properties 里 49 个环境变量目前有 32 个未登记
 * （STORAGE_*、UPLOAD_*、KNOWLEDGE_*、AI_DIAGNOSTICS_* 等），一次性全量强制会让 CI 立刻红一片，
 * 那是另一件事的范围。QA_* 当前是全部登记过的，所以这条检查现在就是绿的，且能挡住
 * "新增问答配置忘了写部署文档"——那种漏配的症状是运维照默认值跑，而默认值恰恰是给开发机
 * 定的（比如检索并行度 4 与连接池 10 的关系）。
 *
 * 扩大覆盖前要先补齐那 32 条文档，不要反过来把这里的前缀放宽。
 */
const qaVariables = new Set(
  [...applicationProperties.matchAll(/\$\{(QA_[A-Z0-9_]*)[:}]/g)].map((match) => match[1]),
);
for (const variable of qaVariables) {
  if (!deployReadme.includes(`\`${variable}\``)) {
    errors.push(
      `deploy/README.md is missing an entry for configuration variable: ${variable}`,
    );
  }
}

/*
 * 会真实计费的测试必须由环境变量守门。
 *
 * @EnabledIfEnvironmentVariable 看的是 System.getenv，不是 Spring 的 Environment——所以本机
 * backend/.env 里的密钥不会让它们跑起来，CI 没有对应 secret 也不会。这条不变量一旦被删掉
 * （或者有人给 CI 加了密钥 secret），CI 就会开始按次计费，而且是在没人注意的情况下。
 *
 * 检查的是"守门存在"，不是"跑没跑"——后者取决于环境，前者是代码里的承诺。
 */
const realApiTests = globSyncRealApiTests();
for (const [name, source] of realApiTests) {
  if (!source.includes("@EnabledIfEnvironmentVariable")) {
    errors.push(
      `${name} performs billable calls but is not gated by @EnabledIfEnvironmentVariable`,
    );
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

function globSyncRealApiTests() {
  const testRoot = new URL("../backend/src/test/java/", import.meta.url);
  const found = [];
  const walk = (dir) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const child = new URL(`${entry.name}${entry.isDirectory() ? "/" : ""}`, dir);
      if (entry.isDirectory()) {
        walk(child);
      } else if (/^Real.*ApiTests\.java$/.test(entry.name)) {
        found.push([entry.name, readFileSync(child, "utf8")]);
      }
    }
  };
  walk(testRoot);
  return found;
}
