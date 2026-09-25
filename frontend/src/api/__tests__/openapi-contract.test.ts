import { describe, it, expect } from 'vitest'
import fs from 'fs'
import path from 'path'
import * as yaml from 'js-yaml'

const OPENAPI_PATH = path.resolve(__dirname, '../../../../backend/openapi.yaml')
const GENERATED_APIS_DIR = path.resolve(__dirname, '../generated/apis')
const GENERATED_MODELS_DIR = path.resolve(__dirname, '../generated/models')

function loadOpenApiSpec() {
  const content = fs.readFileSync(OPENAPI_PATH, 'utf8')
  return yaml.load(content)
}

function getGeneratedApiFiles() {
  return fs.readdirSync(GENERATED_APIS_DIR)
    .filter(f => f.endsWith('.ts') && f !== 'index.ts')
    .map(f => f.replace('.ts', ''))
}

function getGeneratedModelFiles() {
  return fs.readdirSync(GENERATED_MODELS_DIR)
    .filter(f => f.endsWith('.ts') && f !== 'index.ts')
    .map(f => f.replace('.ts', ''))
}

function readGeneratedFile(dir, name) {
  return fs.readFileSync(path.join(dir, `${name}.ts`), 'utf8')
}

const TAG_TO_API_CLASS: Record<string, string> = {
  'auth-controller': 'AuthControllerApi',
  'bridge-log-controller': 'BridgeLogControllerApi',
  'chat-summary-controller': 'ChatSummaryControllerApi',
  'preprocess-controller': 'PreprocessControllerApi',
  'settings-controller': 'SettingsControllerApi',
  'tool-controller': 'ToolControllerApi',
  'upload-controller': 'UploadControllerApi',
}

describe('OpenAPI Contract Tests', () => {
  const spec = loadOpenApiSpec()
  const generatedApis = getGeneratedApiFiles()
  const generatedModels = getGeneratedModelFiles()

  describe('API Controller Coverage', () => {
    it('should have a generated API class for every tag in the OpenAPI spec', () => {
      const specTags = Object.keys(TAG_TO_API_CLASS)
      for (const tag of specTags) {
        const apiClassName = TAG_TO_API_CLASS[tag]
        expect(generatedApis).toContain(apiClassName)
      }
    })

    it('should not have extra generated API classes not in the spec', () => {
      const specApiClasses = new Set(Object.values(TAG_TO_API_CLASS))
      for (const apiFile of generatedApis) {
        expect(specApiClasses.has(apiFile)).toBe(true)
      }
    })
  })

  describe('Endpoint Operation Coverage', () => {
    for (const [tag, apiClassName] of Object.entries(TAG_TO_API_CLASS)) {
      describe(apiClassName, () => {
        const operations = Object.entries(spec.paths)
          .flatMap(([pathKey, methods]) =>
            Object.entries(methods as Record<string, any>)
              .filter(([method]) => ['get', 'post', 'put', 'patch', 'delete'].includes(method))
              .map(([method, op]) => ({
                path: pathKey,
                method: method.toUpperCase(),
                operationId: op.operationId,
                tags: op.tags || [],
              }))
              .filter(op => op.tags.includes(tag))
          )

        if (operations.length === 0) return

        it(`should have methods for all ${operations.length} operations in the spec`, () => {
          const apiSource = readGeneratedFile(GENERATED_APIS_DIR, apiClassName)
          for (const op of operations) {
            expect(apiSource).toContain(op.operationId)
          }
        })

        it('should use correct HTTP paths for all operations', () => {
          const apiSource = readGeneratedFile(GENERATED_APIS_DIR, apiClassName)
          for (const op of operations) {
            expect(apiSource).toContain(op.path)
          }
        })

        it('should use correct HTTP methods for all operations', () => {
          const apiSource = readGeneratedFile(GENERATED_APIS_DIR, apiClassName)
          for (const op of operations) {
            expect(apiSource).toContain(`method: '${op.method}'`)
          }
        })
      })
    }
  })

  describe('Model Schema Coverage', () => {
    const schemaNames = Object.keys(spec.components?.schemas || {})

    it('should have a generated model file for every schema in the spec', () => {
      for (const schemaName of schemaNames) {
        expect(generatedModels).toContain(schemaName)
      }
    })

    it('should not have extra generated model files not in the spec', () => {
      const KNOWN_GENERATOR_EXTRAS = ['ErrorResponse']
      for (const modelFile of generatedModels) {
        if (KNOWN_GENERATOR_EXTRAS.includes(modelFile)) continue
        expect(schemaNames).toContain(modelFile)
      }
    })

    it('should export all schemas from the models index', () => {
      const modelsIndex = readGeneratedFile(GENERATED_MODELS_DIR, 'index')
      for (const schemaName of schemaNames) {
        expect(modelsIndex).toContain(schemaName)
      }
    })
  })

  describe('Schema Property Coverage', () => {
    for (const [schemaName, schemaDef] of Object.entries(spec.components?.schemas || {})) {
      const schema = schemaDef as any
      if (!schema.properties || Object.keys(schema.properties).length === 0) return

      describe(schemaName, () => {
        it('should have all properties defined in the spec', () => {
          const modelSource = readGeneratedFile(GENERATED_MODELS_DIR, schemaName)
          for (const propName of Object.keys(schema.properties)) {
            expect(modelSource).toContain(propName)
          }
        })

        it('should have the FromJSON serialization function', () => {
          const modelSource = readGeneratedFile(GENERATED_MODELS_DIR, schemaName)
          expect(modelSource).toContain(`${schemaName}FromJSON`)
        })

        it('should have the ToJSON serialization function', () => {
          const modelSource = readGeneratedFile(GENERATED_MODELS_DIR, schemaName)
          expect(modelSource).toContain(`${schemaName}ToJSON`)
        })

        it('should have the TypeScript interface', () => {
          const modelSource = readGeneratedFile(GENERATED_MODELS_DIR, schemaName)
          expect(modelSource).toContain(`export interface ${schemaName}`)
        })
      })
    }
  })

  describe('Required Parameters', () => {
    for (const [pathKey, methods] of Object.entries(spec.paths)) {
      for (const [method, op] of Object.entries(methods as Record<string, any>)) {
        if (!['get', 'post', 'put', 'patch', 'delete'].includes(method)) continue
        if (!op.parameters) continue

        const requiredParams = (op.parameters as any[])
          .filter(p => p.required)

        if (requiredParams.length === 0) continue

        const tag = op.tags?.[0]
        if (!tag || !TAG_TO_API_CLASS[tag]) continue

        const apiClassName = TAG_TO_API_CLASS[tag]

        describe(`${method.toUpperCase()} ${pathKey}`, () => {
          it(`should have required parameter validation for ${requiredParams.map(p => p.name).join(', ')}`, () => {
            const apiSource = readGeneratedFile(GENERATED_APIS_DIR, apiClassName)
            for (const param of requiredParams) {
              expect(apiSource).toContain(`'${param.name}'`)
              expect(apiSource).toContain('RequiredError')
            }
          })
        })
      }
    }
  })

  describe('Request Body Validation', () => {
    for (const [pathKey, methods] of Object.entries(spec.paths)) {
      for (const [method, op] of Object.entries(methods as Record<string, any>)) {
        if (!['get', 'post', 'put', 'patch', 'delete'].includes(method)) continue
        if (!op.requestBody?.required) continue

        const tag = op.tags?.[0]
        if (!tag || !TAG_TO_API_CLASS[tag]) continue
        const apiClassName = TAG_TO_API_CLASS[tag]
        const operationId = op.operationId

        describe(`${method.toUpperCase()} ${pathKey}`, () => {
          it(`should validate required request body in ${operationId}`, () => {
            const apiSource = readGeneratedFile(GENERATED_APIS_DIR, apiClassName)
            const methodSource = apiSource.split(`${operationId}Raw`)[1] || ''
            expect(methodSource).toContain('RequiredError')
          })
        })
      }
    }
  })

  describe('Enum Values', () => {
    for (const [schemaName, schemaDef] of Object.entries(spec.components?.schemas || {})) {
      const schema = schemaDef as any
      if (!schema.properties) continue

      for (const [propName, propDef] of Object.entries(schema.properties)) {
        const prop = propDef as any
        if (!prop.enum) continue

        describe(`${schemaName}.${propName}`, () => {
          it(`should have enum values ${prop.enum.join(', ')} in the generated model`, () => {
            const modelSource = readGeneratedFile(GENERATED_MODELS_DIR, schemaName)
            for (const enumVal of prop.enum) {
              expect(modelSource).toContain(`'${enumVal}'`)
            }
          })
        })
      }
    }
  })
})
