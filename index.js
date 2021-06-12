"use strict";
const pulumi = require("@pulumi/pulumi");
const aws = require("@pulumi/aws");

// Import ClojureScript code
const stack = require("./generated/stack.js");

const outputs = stack();
Object.assign(exports, outputs);
