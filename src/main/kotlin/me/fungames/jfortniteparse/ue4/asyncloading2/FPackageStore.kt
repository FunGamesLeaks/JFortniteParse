package me.fungames.jfortniteparse.ue4.asyncloading2

import me.fungames.jfortniteparse.LOG_STREAMING
import me.fungames.jfortniteparse.fileprovider.PakFileProvider
import me.fungames.jfortniteparse.ue4.io.*
import me.fungames.jfortniteparse.ue4.objects.uobject.FPackageId
import me.fungames.jfortniteparse.ue4.objects.uobject.serialization.FMappedName
import me.fungames.jfortniteparse.ue4.objects.uobject.serialization.FNameMap
import me.fungames.jfortniteparse.ue4.reader.FArchive
import me.fungames.jfortniteparse.ue4.reader.FByteArchive
import me.fungames.jfortniteparse.ue4.versions.GAME_UE5_BASE
import me.fungames.jfortniteparse.util.await
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

class FPackageStore(val provider: PakFileProvider) : FOnContainerMountedListener {
    val loadedContainers = hashMapOf<FIoContainerId, FLoadedContainer>()

    val currentCultureNames = mutableListOf<String>()

    val packageNameMapsCritical = Object()

    val storeEntriesMap = hashMapOf<FPackageId, FPackageStoreEntry>()
    val redirectsPackageMap = hashMapOf<FPackageId, FPackageId>()

    val scriptObjectEntriesMap: Map<FPackageObjectIndex, FScriptObjectEntry>

    init {
        val initialLoadArchive: FArchive
        val globalNameMap = FNameMap()
        if (provider.game.game >= GAME_UE5_BASE) {
            initialLoadArchive = FByteArchive(provider.saveChunk(FIoChunkId(0u, 0u, EIoChunkType5.ScriptObjects)))
            globalNameMap.load(initialLoadArchive, FMappedName.EType.Global)
        } else {
            val nameBuffer = provider.saveChunk(FIoChunkId(0u, 0u, EIoChunkType.LoaderGlobalNames))
            val hashBuffer = provider.saveChunk(FIoChunkId(0u, 0u, EIoChunkType.LoaderGlobalNameHashes))
            globalNameMap.load(nameBuffer, hashBuffer, FMappedName.EType.Global)

            initialLoadArchive = FByteArchive(provider.saveChunk(FIoChunkId(0u, 0u, EIoChunkType.LoaderInitialLoadMeta)))
        }

        initialLoadArchive.game = provider.game.game
        val numScriptObjects = initialLoadArchive.readInt32()
        scriptObjectEntriesMap = HashMap(numScriptObjects)
        repeat(numScriptObjects) {
            val entry = FScriptObjectEntry(initialLoadArchive, globalNameMap.nameEntries)
            val mappedName = FMappedName.fromMinimalName(entry.objectName)
            check(mappedName.isGlobal())
            entry.objectName = globalNameMap.getMinimalName(mappedName)

            scriptObjectEntriesMap[entry.globalIndex] = entry
        }

        currentCultureNames.add(Locale.getDefault().toString().replace('_', '-'))
        loadContainers(provider.mountedIoStoreReaders().map { it.containerId })
    }

    fun loadContainers(containers: Iterable<FIoContainerId>) {
        val containersToLoad = containers.filter { it.isValid() }

        if (containersToLoad.isEmpty()) {
            return
        }

        val remaining = AtomicInteger(containersToLoad.size)
        val event = CompletableFuture<Void>()

        for (containerId in containersToLoad) {
            val loadedContainer = loadedContainers.getOrPut(containerId) { FLoadedContainer() }
            LOG_STREAMING.debug("Loading mounted container ID '0x%016X'".format(containerId.value().toLong()))

            val headerChunkId = FIoChunkId(containerId.value(), 0u, if (provider.game.game >= GAME_UE5_BASE) EIoChunkType5.ContainerHeader else EIoChunkType.ContainerHeader)
            val ioBuffer = provider.saveChunk(headerChunkId)

            Thread {
                val containerHeader = FContainerHeader(FByteArchive(ioBuffer).apply { game = provider.game.game })
                loadedContainer.containerNameMap = containerHeader.containerNameMap
                loadedContainer.packageCount = containerHeader.packageCount
                loadedContainer.storeEntries = containerHeader.storeEntries
                synchronized(packageNameMapsCritical) {
                    loadedContainer.storeEntries.forEachIndexed { index, containerEntry ->
                        val packageId = containerHeader.packageIds[index]
                        storeEntriesMap[packageId] = containerEntry
                    }

                    val localizedPackages = currentCultureNames.firstNotNullOfOrNull { containerHeader.culturePackageMap[it] }
                    if (localizedPackages != null) {
                        for ((sourceId, localizedId) in localizedPackages) {
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

        //applyRedirects(redirectsPackageMap)
    }

    override fun onContainerMounted(container: FIoContainerId) {
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
        lateinit var containerNameMap: FNameMap
        lateinit var storeEntries: Array<FPackageStoreEntry>
        var packageCount = 0u
    }
}