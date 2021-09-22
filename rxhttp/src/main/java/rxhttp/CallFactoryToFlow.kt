package rxhttp

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import rxhttp.wrapper.BodyParamFactory
import rxhttp.wrapper.CallFactory
import rxhttp.wrapper.callback.FileOutputStreamFactory
import rxhttp.wrapper.callback.OutputStreamFactory
import rxhttp.wrapper.callback.UriOutputStreamFactory
import rxhttp.wrapper.coroutines.Await
import rxhttp.wrapper.coroutines.setRangeHeader
import rxhttp.wrapper.entity.Progress
import rxhttp.wrapper.entity.ProgressT

/**
 * CallFactory convert Flow
 *
 * User: ljx
 * Date: 2021/9/18
 * Time: 17:31
 */
inline fun <reified T : Any> CallFactory.toFlow(): Flow<T> = toFlow(toClass())

inline fun <reified T : Any> CallFactory.toFlow(await: Await<T>): Flow<T> =
    flow { emit(await.await()) }

@ExperimentalCoroutinesApi
inline fun <reified T : Any> BodyParamFactory.toFlow(
    noinline progress: suspend (Progress) -> Unit
) = toFlowProgress<T>().onEachProgress(progress)

@ExperimentalCoroutinesApi
inline fun <reified T : Any> BodyParamFactory.toFlowProgress(
    await: Await<T> = toClass()
) =
    channelFlow {
        param.setProgressCallback { progress, currentSize, totalSize ->
            trySend(ProgressT<T>(progress, currentSize, totalSize))
        }
        await.await().also { trySend(ProgressT<T>(it)) }
    }.buffer(1, BufferOverflow.DROP_OLDEST)

/**
 * @param destPath Local storage path
 * @param append is append download
 * @param progress Progress callback in suspend method, The callback thread depends on the coroutine thread
 */
fun CallFactory.toFlow(
    destPath: String,
    append: Boolean = false,
    progress: (suspend (Progress) -> Unit)? = null
): Flow<String> = toFlow(FileOutputStreamFactory(destPath), append, progress)

fun CallFactory.toFlow(
    context: Context,
    uri: Uri,
    append: Boolean = false,
    progress: (suspend (Progress) -> Unit)? = null
): Flow<Uri> = toFlow(UriOutputStreamFactory(context, uri), append, progress)

fun <T> CallFactory.toFlow(
    osFactory: OutputStreamFactory<T>,
    append: Boolean = false,
    progress: (suspend (Progress) -> Unit)? = null
): Flow<T> =
    if (progress == null) {
        flow {
            setRangeHeader(osFactory, append)
            emit(toSyncDownload(osFactory).await())
        }.flowOn(Dispatchers.IO)
    } else {
        toFlowProgress(osFactory, append)
            .onEachProgress(progress)
    }

fun CallFactory.toFlowProgress(
    destPath: String,
    append: Boolean = false
): Flow<ProgressT<String>> = toFlowProgress(FileOutputStreamFactory(destPath), append)

fun CallFactory.toFlowProgress(
    context: Context,
    uri: Uri,
    append: Boolean = false
): Flow<ProgressT<Uri>> = toFlowProgress(UriOutputStreamFactory(context, uri), append)

fun <T> CallFactory.toFlowProgress(
    osFactory: OutputStreamFactory<T>,
    append: Boolean = false
): Flow<ProgressT<T>> =
    flow {
        setRangeHeader(osFactory, append)
        toSyncDownload(osFactory) { emit(it) }
            .await().let { emit(ProgressT(it)) }
    }
        .buffer(1, BufferOverflow.DROP_OLDEST)
        .flowOn(Dispatchers.IO)

fun <T> Flow<ProgressT<T>>.onEachProgress(progress: suspend (Progress) -> Unit): Flow<T> =
    onEach { if (it.result == null) progress(it) }
        .mapNotNull { it.result }