#!/usr/bin/env node

const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const projectRoot = process.cwd();
const env = { ...process.env, EXPO_NONINTERACTIVE: '1' };

function runCommand(command, args, options = {}) {
  const result = spawnSync(command, args, {
    stdio: 'inherit',
    cwd: projectRoot,
    env,
    ...options,
  });

  if (result.error) {
    throw result.error;
  }

  if (result.status !== 0) {
    process.exit(result.status ?? 1);
  }
}

if (process.platform !== 'win32') {
  runCommand('npx', ['expo-module', 'prepare']);
  process.exit(0);
}

function removeBuildFolder(relativePath) {
  const absolutePath = path.join(projectRoot, relativePath);
  fs.rmSync(absolutePath, { recursive: true, force: true });
}

function gatherTemplateFiles(dir, relative = '') {
  const entries = fs.readdirSync(dir, { withFileTypes: true });
  return entries.flatMap((entry) => {
    const nextRelative = relative ? `${relative}/${entry.name}` : entry.name;
    const absolutePath = path.join(dir, entry.name);

    if (entry.isDirectory()) {
      return gatherTemplateFiles(absolutePath, nextRelative);
    }

    if (entry.isFile()) {
      return [nextRelative];
    }

    return [];
  });
}

function fileContainsGeneratedComment(filePath) {
  try {
    const content = fs.readFileSync(filePath, 'utf8');
    return content.includes('@generated');
  } catch (error) {
    return false;
  }
}

function copyTemplateFile(sourceRoot, relativePath, optionalTemplates) {
  const sourcePath = path.join(sourceRoot, ...relativePath.split('/'));
  const destinationPath = path.join(projectRoot, ...relativePath.split('/'));
  const destinationDir = path.dirname(destinationPath);
  const destinationExists = fs.existsSync(destinationPath);
  const isOptional = optionalTemplates.has(relativePath);
  const containsGeneratedComment = destinationExists && fileContainsGeneratedComment(destinationPath);

  let shouldCopy = false;
  if (isOptional) {
    shouldCopy = destinationExists && containsGeneratedComment;
  } else {
    shouldCopy = !destinationExists || containsGeneratedComment;
  }

  if (!shouldCopy) {
    return;
  }

  fs.mkdirSync(destinationDir, { recursive: true });
  fs.copyFileSync(sourcePath, destinationPath);
}

removeBuildFolder('build');
['plugin', 'cli', 'utils', 'scripts'].forEach((folder) => {
  removeBuildFolder(path.join(folder, 'build'));
});

runCommand('npx', ['expo-module', 'readme']);

const templateRoot = path.join(projectRoot, 'node_modules', 'expo-module-scripts', 'templates');
const optionalTemplates = new Set(['scripts/with-node.sh']);

if (fs.existsSync(templateRoot)) {
  const templateFiles = gatherTemplateFiles(templateRoot);
  templateFiles.forEach((relativePath) => {
    copyTemplateFile(templateRoot, relativePath, optionalTemplates);
  });
}

runCommand('npx', ['tsc', '--project', 'tsconfig.json']);

['plugin', 'cli', 'utils', 'scripts'].forEach((folder) => {
  const tsconfigPath = path.join(folder, 'tsconfig.json');
  if (fs.existsSync(path.join(projectRoot, tsconfigPath))) {
    runCommand('npx', ['tsc', '--project', tsconfigPath]);
  }
});
