package me.fungames.jfortniteparse.ue4.asyncloading2

import me.fungames.jfortniteparse.LOG_STREAMING
import me.fungames.jfortniteparse.fileprovider.FileProvider
import me.fungames.jfortniteparse.ue4.io.*
import me.fungames.jfortniteparse.ue4.objects.uobject.FPackageId
import me.fungames.jfortniteparse.ue4.reader.FByteArchive
import me.fungames.jfortniteparse.util.await
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

class FPackageStore(
    val provider: FileProvider,
    val globalNameMap: FNameMap) : FOnContainerMountedListener {
    val loadedContainers = hashMapOf<FIoContainerId, FLoadedContainer>()

    val currentCultureNames = mutableListOf<String>()

    val packageNameMapsCritical = Object()

    val storeEntriesMap = hashMapOf<FPackageId, FPackageStoreEntry>()
    val redirectsPackageMap = hashMapOf<FPackageId, FPackageId>()

    // Temporary initial load data
    val scriptObjectEntries = arrayListOf<FScriptObjectEntry>()
    val scriptObjectEntriesMap = hashMapOf<FPackageObjectIndex, FScriptObjectEntry>()

    fun setupCulture() {
        currentCultureNames.clear()
        currentCultureNames.add(Locale.getDefault().toString().replace('_', '-'))
    }

    fun setupInitialLoadData() {
        val initialLoadIoBuffer = provider.saveChunk(FIoChunkId(0u, 0u, EIoChunkType.LoaderInitialLoadMeta))
        val initialLoadArchive = FByteArchive(initialLoadIoBuffer)
        val numScriptObjects = initialLoadArchive.readInt32()
        scriptObjectEntries.ensureCapacity(numScriptObjects)

        repeat(numScriptObjects) {
            scriptObjectEntries.add(FScriptObjectEntry(initialLoadArchive, globalNameMap.nameEntries).also {
                val mappedName = FMappedName.fromMinimalName(it.objectName)
                check(mappedName.isGlobal())
                it.objectName = globalNameMap.getMinimalName(mappedName)

                scriptObjectEntriesMap[it.globalIndex] = it
            })
        }
    }

    fun loadContainers(containers: Iterable<FIoDispatcherMountedContainer>) {
        val containersToLoad = containers.filter { it.containerId.isValid() }

        if (containersToLoad.isEmpty()) {
            return
        }

        val remaining = AtomicInteger(containersToLoad.size)
        val event = CompletableFuture<Void>()

        for (container in containersToLoad) {
            val containerId = container.containerId
            val loadedContainer = loadedContainers.getOrPut(containerId) { FLoadedContainer() }
            if (loadedContainer.bValid && loadedContainer.order >= container.environment.order) {
                LOG_STREAMING.debug("Skipping loading mounted container ID '0x%016X', already loaded with higher order".format(containerId.value().toLong()))
                if (remaining.decrementAndGet() == 0) {
                    event.complete(null)
                }
                continue
            }

            LOG_STREAMING.debug("Loading mounted container ID '0x%016X'".format(containerId.value().toLong()))
            loadedContainer.bValid = true
            loadedContainer.order = container.environment.order

            val headerChunkId = FIoChunkId(containerId.value(), 0u, EIoChunkType.ContainerHeader)
            val ioBuffer = provider.saveChunk(headerChunkId)

            Thread {
                val containerHeader = FContainerHeader(FByteArchive(ioBuffer))

                val bHasContainerLocalNameMap = containerHeader.names.isNotEmpty()
                if (bHasContainerLocalNameMap) {
                    loadedContainer.containerNameMap.load(containerHeader.names, containerHeader.nameHashes, FMappedName.EType.Container)
                }

                loadedContainer.packageCount = containerHeader.packageCount
                loadedContainer.storeEntries = containerHeader.storeEntries
                synchronized(packageNameMapsCritical) {
                    loadedContainer.storeEntries.forEachIndexed { index, containerEntry ->
                        val packageId = containerHeader.packageIds[index]
                        storeEntriesMap[packageId] = containerEntry
                    }

                    var localizedPackages: FSourceToLocalizedPackageIdMap? = null
                    for (cultureName in currentCultureNames) {
                        localizedPackages = containerHeader.culturePackageMap[cultureName]
                        if (localizedPackages != null) {
                            break
                        }
                    }

                    if (localizedPackages != null) {
                        for (pair in localizedPackages) {
                            val sourceId = pair.first
                            val localizedId = pair.second
                            redirectsPackageMap[sourceId] = localizedId
                        }
                    }

                    for (redirect in containerHeader.packageRedirects) {
                        redirectsPackageMap[redirect.first] = redirect.second
                    }
                }

                if (remaining.decrementAndGet() == 0) {
                    event.complete(null)
                }
            }.start()
        }

        event.await()

        applyRedirects(redirectsPackageMap)
    }

    override fun onContainerMounted(container: FIoDispatcherMountedContainer) {
        loadContainers(listOf(container))
    }

    fun applyRedirects(redirects: Map<FPackageId, FPackageId>) {
        synchronized(packageNameMapsCritical) {
            if (redirects.isEmpty()) {
                return
            }

            for ((sourceId, redirectId) in redirects) {
                check(redirectId.isValid())
                val redirectEntry = storeEntriesMap[redirectId]!!
                storeEntriesMap[sourceId] = redirectEntry
            }

            for (storeEntry in storeEntriesMap.values) {
                storeEntry.importedPackages.forEachIndexed { index, importedPackageId ->
                    redirects[importedPackageId]?.also { storeEntry.importedPackages[index] = it }
                }
            }
        }
    }

    fun findStoreEntry(packageId: FPackageId): FPackageStoreEntry? {
        synchronized(packageNameMapsCritical) {
            return storeEntriesMap[packageId]
        }
    }

    fun getRedirectedPackageId(packageId: FPackageId): FPackageId? {
        synchronized(packageNameMapsCritical) {
            return redirectsPackageMap[packageId]
        }
    }

    class FLoadedContainer {
        val containerNameMap = FNameMap()
        lateinit var storeEntries: Array<FPackageStoreEntry>
        var packageCount = 0u
        var order = 0
        var bValid = false
    }
}