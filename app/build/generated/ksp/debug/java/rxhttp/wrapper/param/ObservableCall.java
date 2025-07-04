package rxhttp.wrapper.param;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Observer;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.exceptions.Exceptions;
import io.reactivex.rxjava3.functions.Consumer;
import io.reactivex.rxjava3.internal.disposables.DisposableHelper;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;
import rxhttp.wrapper.BodyParamFactory;
import rxhttp.wrapper.CallFactory;
import rxhttp.wrapper.callback.ProgressCallback;
import rxhttp.wrapper.entity.OkResponse;
import rxhttp.wrapper.entity.Progress;
import rxhttp.wrapper.exception.ProxyException;
import rxhttp.wrapper.parse.OkResponseParser;
import rxhttp.wrapper.parse.Parser;
import rxhttp.wrapper.parse.StreamParser;
import rxhttp.wrapper.utils.LogUtil;

/**
 * User: ljx
 * Date: 2020/9/5
 * Time: 21:59
 */
public final class ObservableCall<T> extends Observable<T> {

    private final Parser<T> parser;
    private final CallFactory callFactory;
    private boolean syncRequest = false;
    //上传/下载进度回调最小周期, 值越小，回调事件越多，设置一个合理值，可避免密集回调
    private int minPeriod = Integer.MIN_VALUE;

    ObservableCall(CallFactory callFactory, Parser<T> parser) {
        this.callFactory = callFactory;
        this.parser = parser;
    }

    @Override
    protected void subscribeActual(Observer<? super T> observer) {
        CallExecuteDisposable<T> d = syncRequest ? new CallExecuteDisposable<>(observer, callFactory, parser) :
            new CallEnqueueDisposable<>(observer, callFactory, parser);
        observer.onSubscribe(d);
        if (d.isDisposed()) {
            return;
        }
        if (minPeriod != Integer.MIN_VALUE && observer instanceof ProgressCallback) {
            ProgressCallback pc = (ProgressCallback) observer;
            Parser<?> parser = this.parser;
            while (parser instanceof OkResponseParser<?>) {
                parser = ((OkResponseParser<?>) parser).parser;
            }
            if (parser instanceof StreamParser) {
                 ((StreamParser<?>) parser).setProgressCallback(minPeriod, pc);
            } else if (callFactory instanceof BodyParamFactory) {
                ((BodyParamFactory) callFactory).getParam().setProgressCallback(minPeriod, pc);
            }
        }
        d.run();
    }
    
    @NotNull
    public ObservableCall<@NotNull OkResponse<@Nullable T>> toObservableOkResponse() { 
        return new ObservableCall<>(callFactory, new OkResponseParser<>(parser));
    }

    @NotNull 
    public ObservableCall<@NotNull T> syncRequest() {
        syncRequest = true;
        return this;
    }

    @NotNull
    public Observable<@NotNull T> onProgress(@NotNull Consumer<Progress<@Nullable T>> progressConsumer) {
        return onProgress(Schedulers.io(), progressConsumer);
    }

    @NotNull
    public Observable<@NotNull T> onProgress(@NotNull Scheduler scheduler, @NotNull Consumer<Progress<@Nullable T>> progressConsumer) {
        return onProgress(2, scheduler, progressConsumer);
    }

    @NotNull
    public Observable<@NotNull T> onMainProgress(@NotNull Consumer<Progress<@Nullable T>> progressConsumer) {
        return onMainProgress(2, progressConsumer);
    }

    @NotNull
    public Observable<@NotNull T> onMainProgress(int capacity, @NotNull Consumer<Progress<@Nullable T>> progressConsumer) {
        return onProgress(capacity, AndroidSchedulers.mainThread(), progressConsumer);
    }
    
    @NotNull
    public Observable<@NotNull T> onMainProgress(int capacity, int minPeriod, @NotNull Consumer<Progress<@Nullable T>> progressConsumer) {
        return onProgress(capacity, minPeriod, AndroidSchedulers.mainThread(), progressConsumer);
    }

    @NotNull
    public Observable<@NotNull T> onProgress(int capacity, @NotNull Scheduler scheduler, @NotNull Consumer<Progress<@Nullable T>> progressConsumer) {
        return onProgress(capacity, 500, scheduler, progressConsumer);
    }

    /**
     * Upload or Download progress callback
     *
     * @param capacity         queue size, must be in [2..100], is invalid when the scheduler is TrampolineScheduler
     * @param minPeriod        minimum period of progress callback, must be between 1 and {@link Integer.MAX_VALUE}, The default value is 500 milliseconds,
     * @param scheduler        the Scheduler to notify Observers on
     * @param progressConsumer progress callback
     * @return the new Observable instance
     */
    @NotNull 
    public Observable<@NotNull T> onProgress(int capacity, int minPeriod, @NotNull Scheduler scheduler, @NotNull Consumer<Progress<@Nullable T>> progressConsumer) {
        if (capacity < 2 || capacity > 100) {
            throw new IllegalArgumentException("capacity must be in [2..100], but it was " + capacity);
        }
        if (minPeriod < 0) {
            throw new IllegalArgumentException("minPeriod must be between 0 and Integer.MAX_VALUE, but it was " + minPeriod);
        }
        Objects.requireNonNull(scheduler, "scheduler is null");
        Parser<?> streamParser = parser;
        while (streamParser instanceof OkResponseParser<?>) {
            streamParser = ((OkResponseParser<?>) streamParser).parser;
        }
        if (!(streamParser instanceof StreamParser) && !(callFactory instanceof BodyParamFactory)) {
            throw new UnsupportedOperationException("parser is " + streamParser.getClass().getName() + ", callFactory is " + callFactory.getClass().getName());
        }
        this.minPeriod = minPeriod;
        return new ObservableProgress<>(this, capacity, scheduler, progressConsumer);
    }

    private static class CallEnqueueDisposable<T> extends CallExecuteDisposable<T> implements Callback {

        CallEnqueueDisposable(Observer<? super T> downstream, CallFactory callFactory, Parser<T> parser) {
            super(downstream, callFactory, parser);
        }

        @Override
        public void onResponse(@NotNull Call call, @NotNull Response response) {
            try {
                T t = Objects.requireNonNull(parser.onParse(response), "The onParse function returned a null value.");
                if (!disposed) {
                    downstream.onNext(t);
                }
                if (!disposed) {
                    downstream.onComplete();
                }
            } catch (Throwable t) {
                onError(call, t);
            }
        }

        @Override
        public void onFailure(@NotNull Call call, @NotNull IOException e) {
            onError(call, e);
        }

        @Override
        public void run() {
            try {
                call = callFactory.newCall();
                call.enqueue(this);
            } catch (Throwable e) {
                onError(call, e);
            }
        }
    }


    private static class CallExecuteDisposable<T> implements Disposable {

        protected final Observer<? super T> downstream;
        protected final Parser<T> parser;
        protected final CallFactory callFactory;
        protected volatile boolean disposed;
        protected Call call;
        private final AtomicReference<Disposable> upstream;

        CallExecuteDisposable(Observer<? super T> downstream, CallFactory callFactory, Parser<T> parser) {
            this.downstream = downstream;
            this.callFactory = callFactory;
            this.parser = parser;
            upstream = new AtomicReference<>();
        }

        public void run() {
            try {
                call = callFactory.newCall();
                Response response = call.execute();
                T t = Objects.requireNonNull(parser.onParse(response), "The onParse function returned a null value.");
                if (!disposed) {
                    downstream.onNext(t);
                }
                if (!disposed) {
                    downstream.onComplete();
                }
            } catch (Throwable e) {
                onError(call, e);
            }
        }

        void onError(@Nullable Call call, Throwable e) {
            LogUtil.logCall(call, e);
            Exceptions.throwIfFatal(e);
            if (!disposed) {
                downstream.onError(e);
            } else {
                RxJavaPlugins.onError(e);
            }
        }

        @Override
        public void dispose() {
            DisposableHelper.dispose(upstream);
            disposed = true;
            if (call != null)
                call.cancel();
        }

        @Override
        public boolean isDisposed() {
            return disposed;
        }

        public void setDisposable(Disposable d) {
            DisposableHelper.setOnce(upstream, d);
        }
    }
}
