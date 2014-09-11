package asset.pipeline
import groovy.util.logging.Log4j
import asset.pipeline.processors.UglifyJsProcessor
import asset.pipeline.processors.CssMinifyPostProcessor
@Log4j
class AssetCompiler {
	def includeRules = [:]
	def excludeRules = [:]

	def options = [:]
	def eventListener
	def filesToProcess = []
	Properties manifestProperties

	AssetCompiler(options=[:], eventListener = null) {
		this.eventListener = eventListener
		this.options = options
		if(!options.compileDir) {
			options.compileDir = "target/assets"
		}
		if(!options.excludesGzip) {
			options.excludesGzip = ['png', 'jpg','jpeg', 'gif', 'zip', 'gz']
		} else {
			options.excludesGzip += ['png', 'jpg','jpeg', 'gif', 'zip', 'gz']
		}
		// Load in additional assetSpecs
		options.specs?.each { spec ->
			def specClass = this.class.classLoader.loadClass(spec)
			if(specClass) {
				AssetHelper.assetSpecs += specClass
			}
		}
		manifestProperties = new Properties()
	}

	void compile() {
		def assetDir           = initializeWorkspace()
		def uglifyJsProcessor  = new UglifyJsProcessor()
		def minifyCssProcessor = new CssMinifyPostProcessor()

		filesToProcess = this.getAllAssets()
		// Lets clean up assets that are no longer being compiled
		removeDeletedFiles(filesToProcess)

		for(int index = 0 ; index < filesToProcess.size() ; index++) {
			def assetFile = filesToProcess[index]
			def fileName = assetFile.path
			eventListener?.triggerEvent("StatusUpdate", "Processing File ${index+1} of ${filesToProcess.size()} - ${fileName}")

			def digestName
			def isUnchanged    = false
			def extension      = AssetHelper.extensionFromURI(fileName)
			fileName           = AssetHelper.nameWithoutExtension(fileName)
			def fileSystemName = fileName.replace(AssetHelper.DIRECTIVE_FILE_SEPARATOR, File.separator)


			if(assetFile) {
				def fileData
				if(!(assetFile instanceof GenericAssetFile)) {
					if(assetFile.compiledExtension) {
						extension = assetFile.compiledExtension
						fileName = AssetHelper.fileNameWithoutExtensionFromArtefact(fileName,assetFile)
					}
					def contentType = (assetFile.contentType instanceof String) ? assetFile.contentType : assetFile.contentType[0]
					def directiveProcessor = new DirectiveProcessor(contentType, this)
					fileData   = directiveProcessor.compile(assetFile)
					digestName = AssetHelper.getByteDigest(fileData.bytes)

					def existingDigestFile = manifestProperties.getProperty("${fileName}.${extension}")
					if(existingDigestFile && existingDigestFile == "${fileName}-${digestName}.${extension}") {
						isUnchanged=true
					}

					if(fileName.indexOf(".min") == -1 && contentType == 'application/javascript' && options.minifyJs && !isUnchanged) {
						def newFileData = fileData
						try {
							eventListener?.triggerEvent("StatusUpdate", "Uglifying File ${index+1} of ${filesToProcess.size()} - ${fileName}")
							newFileData = uglifyJsProcessor.process(fileData, options.minifyOptions ?: [:])
						} catch(e) {
							log.error("Uglify JS Exception", e)
							newFileData = fileData
						}
						fileData = newFileData
					} else if(fileName.indexOf(".min") == -1 && contentType == 'text/css' && options.minifyCss && !isUnchanged) {
						def newFileData = fileData
						try {
							eventListener?.triggerEvent("StatusUpdate", "Minifying File ${index+1} of ${filesToProcess.size()} - ${fileName}")
							newFileData = minifyCssProcessor.process(fileData)
						} catch(e) {
							log.error("Minify CSS Exception", e)
							newFileData = fileData
						}
						fileData = newFileData
					}

					if(assetFile.encoding) {
						fileData = fileData.getBytes(assetFile.encoding)
					} else {
						fileData = fileData.bytes
					}

				} else {
					digestName = AssetHelper.getByteDigest(assetFile.bytes)
					def existingDigestFile = manifestProperties.getProperty("${fileName}.${extension}")
					if(existingDigestFile && existingDigestFile == "${fileName}-${digestName}.${extension}") {
						isUnchanged=true
					}
				}

				if(!isUnchanged) {
					def outputFileName = fileName
					if(extension) {
						outputFileName = "${fileSystemName}.${extension}"
					}
					def outputFile = new File(options.compileDir, "${outputFileName}")

					def parentTree = new File(outputFile.parent)
					parentTree.mkdirs()
					outputFile.createNewFile()

					if(fileData) {
						def outputStream = outputFile.newOutputStream()
						outputStream.write(fileData, 0 , fileData.length)
						outputStream.flush()
						outputStream.close()
					} else {
						if(assetFile instanceof GenericAssetFile) {
							outputFile.bytes = assetFile.bytes
						} else {
							outputFile.bytes = assetFile.inputStream.bytes
							digestName = AssetHelper.getByteDigest(assetFile.inputStream.bytes)
						}
					}

					if(extension) {
						try {
							def digestedFile = new File(options.compileDir,"${fileSystemName}-${digestName}${extension ? ('.' + extension) : ''}")
							digestedFile.createNewFile()
							AssetHelper.copyFile(outputFile, digestedFile)

							manifestProperties.setProperty("${fileName}.${extension}", "${fileName}-${digestName}${extension ? ('.' + extension) : ''}")

							// Zip it Good!
							if(!options.excludesGzip.find{ it.toLowerCase() == extension.toLowerCase()}) {
								eventListener?.triggerEvent("StatusUpdate","Compressing File ${index+1} of ${filesToProcess.size()} - ${fileName}")
								createCompressedFiles(outputFile, digestedFile)
							}


						} catch(ex) {
							log.error("Error Compiling File ${fileName}.${extension}",ex)
						}
					}
				}

			}

		}

		saveManifest()
		eventListener?.triggerEvent("StatusUpdate","Finished Precompiling Assets")

  }

  private initializeWorkspace() {
		 // Check for existing Compiled Assets
	  def assetDir = new File(options.compileDir)
	  if(assetDir.exists()) {
		def manifestFile = new File(options.compileDir,"manifest.properties")
		if(manifestFile.exists())
			manifestProperties.load(manifestFile.newDataInputStream())
	  } else {
		assetDir.mkdirs()
	  }
	  return assetDir
  }

	def getIncludesForPathKey(String key) {
		def includes = []
		def defaultIncludes = includeRules.default
		if(defaultIncludes) {
			includes += defaultIncludes
		}
		if(includeRules[key]) {
			includes += includeRules[key]
		}
		return includes.unique()
	}

	def getExcludesForPathKey(String key) {
		def excludes = ["**/.*","**/.DS_Store", 'WEB-INF/**/*', '**/META-INF/*', '**/_*.*','**/.svn/**']
		def defaultExcludes = excludeRules.default
		if(defaultExcludes) {
			excludes += defaultExcludes
		}
		if(excludeRules[key]) {
			excludes += excludeRules[key]
		}

		return excludes.unique()
	}


	def getAllAssets() {
		def filesToProcess = []
		AssetPipelineConfigHolder.resolvers.each { resolver ->
			def files = resolver.scanFiles(getExcludesForPathKey(resolver.name),getIncludesForPathKey(resolver.name))
			filesToProcess += files
		}

		filesToProcess.unique{ a,b -> a.path <=> b.path}
		return filesToProcess //Make sure we have a unique set
	}

	private saveManifest() {
		// Update Manifest
		def manifestFile = new File(options.compileDir,'manifest.properties')
		manifestProperties.store(manifestFile.newWriter(),"")
	}

	private createCompressedFiles(outputFile, digestedFile) {
		def targetStream  = new java.io.ByteArrayOutputStream()
		def zipStream     = new java.util.zip.GZIPOutputStream(targetStream)
		def zipFile       = new File("${outputFile.getAbsolutePath()}.gz")
		def zipFileDigest = new File("${digestedFile.getAbsolutePath()}.gz")

		zipStream.write(outputFile.bytes)
		zipFile.createNewFile()
		zipFileDigest.createNewFile()
		zipStream.finish()

		zipFile.bytes = targetStream.toByteArray()
		AssetHelper.copyFile(zipFile, zipFileDigest)
		targetStream.close()
	}

	private removeDeletedFiles(filesToProcess) {
		def compiledFileNames = filesToProcess.collect { assetFile ->
			def fileName  = assetFile.path
			def extension   = AssetHelper.extensionFromURI(fileName)
			fileName        = AssetHelper.nameWithoutExtension(fileName)

			if(assetFile && !(assetFile instanceof GenericAssetFile) && assetFile.compiledExtension) {
				extension = assetFile.compiledExtension
				fileName = AssetHelper.fileNameWithoutExtensionFromArtefact(fileName,assetFile)
			}
			return "${fileName}.${extension}"
		}

		def propertiesToRemove = []
		manifestProperties.keySet().each { compiledUri ->
			def compiledName = 	compiledUri.replace(AssetHelper.DIRECTIVE_FILE_SEPARATOR,File.separator)

			def fileFound = compiledFileNames.find{ it == compiledName.toString()}
			if(!fileFound) {
				def digestedUri = manifestProperties.getProperty(compiledName)
				def digestedName = digestedUri.replace(AssetHelper.DIRECTIVE_FILE_SEPARATOR,File.separator)
				def compiledFile = new File(options.compileDir, compiledName)
				def digestedFile = new File(options.compileDir, digestedName)
				def zippedFile = new File(options.compileDir, "${compiledName}.gz")
				def zippedDigestFile = new File(options.compileDir, "${digestedName}.gz")
				if(compiledFile.exists()) {
					compiledFile.delete()
				}
				if(digestedFile.exists()) {
					digestedFile.delete()
				}
				if(zippedFile.exists()) {
					zippedFile.delete()
				}
				if(zippedDigestFile.exists()) {
					zippedDigestFile.delete()
				}
				propertiesToRemove << compiledName
			}
		}

		propertiesToRemove.each {
			manifestProperties.remove(it)
		}
	}

}