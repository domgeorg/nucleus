package nucleus.presenter;

import android.os.Bundle;
import android.support.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import nucleus.presenter.delivery.DeliverFirst;
import nucleus.presenter.delivery.DeliverLatestCache;
import nucleus.presenter.delivery.DeliverReply;
import nucleus.presenter.delivery.Delivery;
import rx.Observable;
import rx.Subscription;
import rx.functions.Action1;
import rx.functions.Action2;
import rx.functions.Func0;
import rx.subjects.BehaviorSubject;
import rx.subscriptions.CompositeSubscription;

/**
 * This is an extension of {@link Presenter} which provides RxJava functionality.
 *
 * @param <View> a type of view
 */
public class RxPresenter<View> extends Presenter<View> {

    private static final String REQUESTED_KEY = RxPresenter.class.getName() + "#requested";

    private final BehaviorSubject<View> view = BehaviorSubject.create();
    private final CompositeSubscription subscriptions = new CompositeSubscription();

    private final HashMap<Integer, Func0<Subscription>> restartables = new HashMap<>();
    private final HashMap<Integer, Subscription> restartableSubscriptions = new HashMap<>();
    private final ArrayList<Integer> requested = new ArrayList<>();

    /**
     * Returns an observable that emits the current attached view during {@link #onTakeView(Object)}
     * and null during {@link #onDropView()}.
     *
     * @return an observable that emits the attached view or null.
     */
    public Observable<View> view() {
        return view;
    }

    /**
     * Registers a subscription to automatically unsubscribe it during onDestroy.
     * See {@link CompositeSubscription#add(Subscription) for details.}
     *
     * @param subscription a subscription to add.
     */
    public void add(Subscription subscription) {
        subscriptions.add(subscription);
    }

    /**
     * Removes and unsubscribes a subscription that has been registered with {@link #add} previously.
     * See {@link CompositeSubscription#remove(Subscription)} for details.
     *
     * @param subscription a subscription to remove.
     */
    public void remove(Subscription subscription) {
        subscriptions.remove(subscription);
    }

    /**
     * A restartable is any RxJava observable that can be requested (subscribed) and
     * should be automatically restarted (re-subscribed) after a process restart if
     * it was still subscribed at the moment of saving presenter's state.
     *
     * Registers a factory. Re-subscribes the restartable after the process restart.
     *
     * @param restartableId id of the restartable
     * @param factory       factory of the restartable
     */
    public void restartable(int restartableId, Func0<Subscription> factory) {
        restartables.put(restartableId, factory);
        if (requested.contains(restartableId))
            start(restartableId);
    }

    /**
     * Starts the given restartable.
     *
     * @param restartableId id of the restartable
     */
    public void start(int restartableId) {
        stop(restartableId);
        requested.add(restartableId);
        restartableSubscriptions.put(restartableId, restartables.get(restartableId).call());
    }

    /**
     * Unsubscribes a restartable
     *
     * @param restartableId id of a restartable.
     */
    public void stop(int restartableId) {
        requested.remove((Integer)restartableId);
        Subscription subscription = restartableSubscriptions.get(restartableId);
        if (subscription != null)
            subscription.unsubscribe();
    }

    public <T> void restartableFirst(int restartableId, Func0<Observable<T>> observableFactory,
        Action2<View, T> onNext, Action2<View, Throwable> onError) {

        restartable(restartableId, observableFactory, this.<T>deliverFirst(), onNext, onError);
    }

    public <T> void restartableCache(int restartableId, Func0<Observable<T>> observableFactory,
        Action2<View, T> onNext, Action2<View, Throwable> onError) {

        restartable(restartableId, observableFactory, this.<T>deliverLatestCache(), onNext, onError);
    }

    public <T> void restartableReplay(int restartableId, Func0<Observable<T>> observableFactory,
        Action2<View, T> onNext, Action2<View, Throwable> onError) {

        restartable(restartableId, observableFactory, this.<T>deliverReply(), onNext, onError);
    }

    public <T> void restartable(final int restartableId, final Func0<Observable<T>> observableFactory,
        final Observable.Transformer<T, Delivery<View, T>> transformer,
        final Action2<View, T> onNext, final Action2<View, Throwable> onError) {

        restartable(restartableId, new Func0<Subscription>() {
            @Override
            public Subscription call() {
                return observableFactory.call()
                    .compose(transformer)
                    .subscribe(new Action1<Delivery<View, T>>() {
                        @Override
                        public void call(Delivery<View, T> delivery) {
                            delivery.split(onNext, onError);
                        }
                    });
            }
        });
    }

    public <T> DeliverLatestCache<View, T> deliverLatestCache() {
        return new DeliverLatestCache<>(view);
    }

    public <T> DeliverFirst<View, T> deliverFirst() {
        return new DeliverFirst<>(view);
    }

    public <T> DeliverReply<View, T> deliverReply() {
        return new DeliverReply<>(view);
    }

    public <T> Action1<Delivery<View, T>> split(final Action2<View, T> onNext, @Nullable final Action2<View, Throwable> onError) {
        return new Action1<Delivery<View, T>>() {
            @Override
            public void call(Delivery<View, T> delivery) {
                delivery.split(onNext, onError);
            }
        };
    }

    public <T> Action1<Delivery<View, T>> split(Action2<View, T> onNext) {
        return split(onNext, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onCreate(Bundle savedState) {
        if (savedState != null)
            requested.addAll(savedState.getIntegerArrayList(REQUESTED_KEY));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        view.onCompleted();
        subscriptions.unsubscribe();
        for (Map.Entry<Integer, Subscription> entry : restartableSubscriptions.entrySet())
            entry.getValue().unsubscribe();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onSave(Bundle state) {
        super.onSave(state);
        for (int i = requested.size() - 1; i >= 0; i--) {
            Subscription subscription = restartableSubscriptions.get(i);
            if (subscription != null && subscription.isUnsubscribed())
                requested.remove((Integer)i);
        }
        state.putIntegerArrayList(REQUESTED_KEY, requested);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onTakeView(View view) {
        super.onTakeView(view);
        this.view.onNext(view);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onDropView() {
        super.onDropView();
        view.onNext(null);
    }
}
