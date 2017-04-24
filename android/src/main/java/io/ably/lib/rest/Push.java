package io.ably.lib.rest;

import com.google.gson.JsonObject;

import io.ably.lib.http.Http;
import io.ably.lib.http.HttpUtils;
import io.ably.lib.types.Callback;
import io.ably.lib.types.RegistrationToken;
import io.ably.lib.types.ErrorInfo;
import io.ably.lib.util.Log;
import io.ably.lib.util.Serialisation;
import io.ably.lib.util.IntentUtils;

import android.content.SharedPreferences;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.preference.PreferenceManager;
import android.support.v4.content.LocalBroadcastManager;

import java.util.ArrayDeque;
import java.lang.reflect.Constructor;

/**
 * Created by tcard on 3/2/17.
 */
public class Push extends PushBase {
    public Push(AblyRest rest) {
        super(rest);
    }

    public void activate(Context context) {
        activate(context, false);
    }

    public void activate(Context context, boolean useCustomRegisterer) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        getStateMachine(context).handleEvent(ActivationStateMachine.CalledActivate.useCustomRegisterer(useCustomRegisterer, prefs));
    }

    public void deactivate(Context context) {
        deactivate(context, false);
    }

    public void deactivate(Context context, boolean useCustomDeregisterer) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        getStateMachine(context).handleEvent(ActivationStateMachine.CalledDeactivate.useCustomDeregisterer(useCustomDeregisterer, prefs));
    }

    public void onNewRegistrationToken(Context context, RegistrationToken.Type type, String token) {
        RegistrationToken previous = rest.device(context).getRegistrationToken();
        if (previous != null) {
            if (previous.type != type) {
                Log.e(TAG, "trying to register device with " + type + ", but it was already registered with " + previous.type);
                return;
            }
            if (previous.token.equals(token)) {
                return;
            }
        }
        rest.device(context).setAndPersistRegistrationToken(context, new RegistrationToken(type, token));
        getStateMachine(context).handleEvent(new ActivationStateMachine.GotPushDeviceDetails());
    }

    /**
     * ActivationStateMachine reacts to events from the user (calls to activate, deactivate, and
     * their optional callbacks), from services (G/FCM registration tokens) and from its internal
     * activity (REST requests).
     *
     * We don't implement this abstract state machine as "normal" procedural code because, while
     * it's interacted with from a Push instance, it is associated with the device (it outlives both
     * the instance and the whole application process); given that the process this machine is
     * being executed on can shut down at any moment, and then we need to be able to take over from
     * a new process, we can't store state in the process itself (in memory, as normal local
     * variables, callback objects, etc.).
     *
     * What we do instead is to reify the state machine. The current state is represented by a
     * (stateless itself, of course) class instance. Each state class knows how to transition to
     * another state (and maybe make some side effect) when an event occurs. If the state doesn't
     * transition to any state, the event is queued, and on each transition the new state attempts
     * to handle those queued events. We persist on disk the name of the current state class and
     * the pending events on transitions, so that it can be shut down and brought back again
     * at any point.
     *
     * Not all states should be persisted, however. Concretely, states that wait for a HTTP request
     * to finish, and thus transition when handling an event fired when the request finishes,
     * wouldn't ever transition if the process dies in the middle of the HTTP request, since the
     * request wouldn't ever finish. While it's not strictly necessary to model those states as
     * reified States of this state machine, we do it anyway for uniformness, and to more
     * straightforwardly implement the abstract state machine from the spec.
     */
    static class ActivationStateMachine {
        static ActivationStateMachine INSTANCE = null;

        static class CalledActivate extends Event {
            static CalledActivate useCustomRegisterer(boolean use, SharedPreferences prefs) {
                prefs.edit().putBoolean(PersistKeys.USE_CUSTOM_REGISTERER, use).apply();
                return new CalledActivate();
            }
        }

        static class CalledDeactivate extends Event {
            static CalledDeactivate useCustomDeregisterer(boolean use, SharedPreferences prefs) {
                prefs.edit().putBoolean(PersistKeys.USE_CUSTOM_DEREGISTERER, use).apply();
                return new CalledDeactivate();
            }
        }

        static class GotPushDeviceDetails extends Event {}

        static class GotUpdateToken extends Event {
            final String updateToken;
            GotUpdateToken(String token) { this.updateToken = token; }
        }

        static class GettingUpdateTokenFailed extends ErrorEvent {
            GettingUpdateTokenFailed(ErrorInfo reason) { super(reason); }
        }

        static class RegistrationUpdated extends Event {}

        static class UpdatingRegistrationFailed extends ErrorEvent {
            UpdatingRegistrationFailed(ErrorInfo reason) { super(reason); }
        }

        static class Deregistered extends Event {}

        static class DeregistrationFailed extends ErrorEvent {
            DeregistrationFailed(ErrorInfo reason) { super(reason); }
        }

        private abstract static class Event {};

        private abstract static class ErrorEvent extends Event {
            final ErrorInfo reason;
            ErrorEvent(ErrorInfo reason) { this.reason = reason; }
        }

        private static class NotActivated extends PersistentState {
            public NotActivated(ActivationStateMachine machine) { super(machine); }
            State transition(Event event) {
                if (event instanceof CalledDeactivate) {
                    machine.callDeactivatedCallback(null);
                    return this;
                } else if (event instanceof CalledActivate) {
                    LocalDevice device = rest.device(machine.context);

                    if (device.updateToken != null) {
                        // Already registered.
                        return new WaitingForNewPushDeviceDetails(machine);
                    }

                    if (device.getRegistrationToken() != null) {
                        machine.handleEvent(new GotPushDeviceDetails());
                    }

                    return new WaitingForPushDeviceDetails(machine);
                }
                return null;
            }
        }

        private static class WaitingForPushDeviceDetails extends PersistentState {
            public WaitingForPushDeviceDetails(ActivationStateMachine machine) { super(machine); }
            State transition(final Event event) {
                if (event instanceof CalledActivate) {
                    return this;
                } else if (event instanceof CalledDeactivate) {
                    machine.callDeactivatedCallback(null);
                    return new NotActivated(machine);
                } else if (event instanceof GotPushDeviceDetails) {
                    final DeviceDetails device = rest.device(machine.context);

                    if (machine.prefs.getBoolean(PersistKeys.USE_CUSTOM_REGISTERER, false)) {
                        machine.useCustomRegisterer(device, true);
                    } else {
                        Http.RequestBody body = rest.http.requestBodyFromGson(device.toJsonObject());
                        rest.asyncHttp.post("/push/deviceRegistrations", HttpUtils.defaultAcceptHeaders(rest.options.useBinaryProtocol), null, body, new Serialisation.HttpResponseHandler<JsonObject>(), new Callback<JsonObject>() {
                            @Override
                            public void onSuccess(JsonObject response) {
                                Log.i(TAG, "registered " + device.id);
                                machine.handleEvent(new GotUpdateToken(response.getAsJsonPrimitive("updateToken").getAsString()));
                            }
                            @Override
                            public void onError(ErrorInfo reason) {
                                Log.e(TAG, "error registering " + device.id + ": " + reason.toString());
                                machine.handleEvent(new GettingUpdateTokenFailed(reason));
                            }
                        });
                    }

                    return new WaitingForUpdateToken(machine);
                }
                return null;
            }
        }

        private static class WaitingForUpdateToken extends State {
            public WaitingForUpdateToken(ActivationStateMachine machine) { super(machine); }
            State transition(Event event) {
                if (event instanceof CalledActivate) {
                    return this;
                } else if (event instanceof GotUpdateToken) {
                    LocalDevice device = rest.device(machine.context);
                    device.setAndPersistUpdateToken(machine.context, ((GotUpdateToken) event).updateToken);
                    machine.callActivatedCallback(null);
                    return new WaitingForNewPushDeviceDetails(machine);
                } else if (event instanceof GettingUpdateTokenFailed) {
                    machine.callActivatedCallback(((GettingUpdateTokenFailed) event).reason);
                    return new NotActivated(machine);
                }
                return null;
            }
        }

        private static class WaitingForNewPushDeviceDetails extends PersistentState {
            public WaitingForNewPushDeviceDetails(ActivationStateMachine machine) { super(machine); }
            State transition(Event event) {
                if (event instanceof CalledActivate) {
                    machine.callActivatedCallback(null);
                    return this;
                } else if (event instanceof CalledDeactivate) {
                    LocalDevice device = rest.device(machine.context);
                    deregister(machine, device);
                    return new WaitingForDeregistration(machine, this);
                } else if (event instanceof GotPushDeviceDetails) {
                    DeviceDetails device = rest.device(machine.context);

                    updateRegistration(machine, device);

                    return new WaitingForRegistrationUpdate(machine);
                }
                return null;
            }
        }

        private static class WaitingForRegistrationUpdate extends State {
            public WaitingForRegistrationUpdate(ActivationStateMachine machine) { super(machine); }
            State transition(Event event) {
                if (event instanceof CalledActivate) {
                    machine.callActivatedCallback(null);
                    return this;
                } else if (event instanceof RegistrationUpdated) {
                    return new WaitingForNewPushDeviceDetails(machine);
                } else if (event instanceof UpdatingRegistrationFailed) {
                    // TODO: Here we could try to recover ourselves if the error is e. g.
                    // a networking error. Just notify the user for now.
                    machine.callUpdateRegistrationFailedCallback(((UpdatingRegistrationFailed) event).reason);
                    return new AfterRegistrationUpdateFailed(machine);
                }
                return null;
            }
        }

        private static class AfterRegistrationUpdateFailed extends PersistentState {
            public AfterRegistrationUpdateFailed(ActivationStateMachine machine) { super(machine); }
            State transition(Event event) {
                if (event instanceof CalledActivate || event instanceof GotPushDeviceDetails) {
                    updateRegistration(machine, rest.device(machine.context));
                    return new WaitingForRegistrationUpdate(machine);
                } else if (event instanceof CalledDeactivate) {
                    deregister(machine, rest.device(machine.context));
                    return new WaitingForDeregistration(machine, this);
                }
                return null;
            }
        }

        private static class WaitingForDeregistration extends State {
            private State previousState;

            public WaitingForDeregistration(ActivationStateMachine machine, State previousState) {
                super(machine);
                this.previousState = previousState;
            }

            State transition(Event event) {
                if (event instanceof CalledDeactivate) {
                    return this;
                } else if (event instanceof Deregistered) {
                    LocalDevice device = rest.device(machine.context);
                    device.setAndPersistUpdateToken(machine.context, null);
                    machine.callDeactivatedCallback(null);
                    return new NotActivated(machine);
                } else if (event instanceof DeregistrationFailed) {
                    machine.callDeactivatedCallback(((DeregistrationFailed) event).reason);
                    return previousState;
                }
                return null;
            }
        }

        private static abstract class State {
            protected final ActivationStateMachine machine;
            protected final AblyRest rest;

            // Returns null if not handled.
            public State(ActivationStateMachine machine) {
                this.machine = machine;
                rest = machine.rest;
            }

            abstract State transition(Event event);
        }

        private static abstract class PersistentState extends State {
            PersistentState(ActivationStateMachine machine) { super(machine); }
        }

        private void callActivatedCallback(ErrorInfo reason) {
            sendErrorIntent("PUSH_ACTIVATE", reason);
        }

        private void callDeactivatedCallback(ErrorInfo reason) {
            sendErrorIntent("PUSH_DEACTIVATE", reason);
        }

        private void callUpdateRegistrationFailedCallback(ErrorInfo reason) {
            sendErrorIntent("PUSH_UPDATE_FAILED", reason);
        }

        private void sendErrorIntent(String name, ErrorInfo error) {
            Intent intent = new Intent();
            IntentUtils.addErrorInfo(intent, error);
            sendIntent(name, intent);
        }

        private void useCustomRegisterer(final DeviceDetails device, final boolean isNew) {
            registerOnceReceiver("PUSH_UPDATE_TOKEN", new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    ErrorInfo error = IntentUtils.getErrorInfo(intent);
                    if (error == null) {
                        Log.i(TAG, "custom registration for " + device.id);
                        if (isNew) {
                            handleEvent(new GotUpdateToken(intent.getStringExtra("updateToken")));
                        } else {
                            handleEvent(new RegistrationUpdated());
                        }
                    } else {
                        Log.e(TAG, "error from custom registration for " + device.id + ": " + error.toString());
                        if (isNew) {
                            handleEvent(new GettingUpdateTokenFailed(error));
                        } else {
                            handleEvent(new UpdatingRegistrationFailed(error));
                        }
                    }
                }
            });

            Intent intent = new Intent();
            intent.putExtra("isNew", isNew);
            sendIntent("PUSH_REGISTER_DEVICE", intent);
        }

        private void useCustomDeregisterer(final DeviceDetails device) {
            registerOnceReceiver("PUSH_DEVICE_DEREGISTERED", new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    ErrorInfo error = IntentUtils.getErrorInfo(intent);
                    if (error == null) {
                        Log.i(TAG, "custom deregistration for " + device.id);
                        handleEvent(new Deregistered());
                    } else {
                        Log.e(TAG, "error from custom deregisterer for " + device.id + ": " + error.toString());
                        handleEvent(new DeregistrationFailed(error));
                    }
                }
            });

            Intent intent = new Intent();
            sendIntent("PUSH_DEREGISTER_DEVICE", intent);
        }

        private void sendIntent(String name, Intent intent) {
            intent.setAction("io.ably.broadcast." + name);
            LocalBroadcastManager.getInstance(context.getApplicationContext()).sendBroadcast(intent);
        }

        private void registerOnceReceiver(String name, final BroadcastReceiver receiver) {
            BroadcastReceiver onceReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    LocalBroadcastManager.getInstance(context.getApplicationContext()).unregisterReceiver(this);
                    receiver.onReceive(context, intent);
                }
            };
            IntentFilter filter = new IntentFilter("io.ably.broadcast." + name);
            LocalBroadcastManager.getInstance(context.getApplicationContext()).registerReceiver(onceReceiver, filter);
        }

        private static void updateRegistration(final ActivationStateMachine machine, final DeviceDetails device) {
            if (machine.prefs.getBoolean(PersistKeys.USE_CUSTOM_REGISTERER, false)) {
                machine.useCustomRegisterer(device, false);
            } else {
                Http.RequestBody body = machine.rest.http.requestBodyFromGson(device.pushRecipientJsonObject());
                machine.rest.asyncHttp.patch("/push/deviceRegistrations/" + device.id, HttpUtils.defaultAcceptHeaders(machine.rest.options.useBinaryProtocol), null, body, null, new Callback<Void>() {
                    @Override
                    public void onSuccess(Void response) {
                        Log.i(TAG, "updated registration " + device.id);
                        machine.handleEvent(new RegistrationUpdated());
                    }
                    @Override
                    public void onError(ErrorInfo reason) {
                        Log.e(TAG, "error updating registration " + device.id + ": " + reason.toString());
                        machine.handleEvent(new UpdatingRegistrationFailed(reason));
                    }
                });
            }
        }

        private static void deregister(final ActivationStateMachine machine, final DeviceDetails device) {
            if (machine.prefs.getBoolean(PersistKeys.USE_CUSTOM_DEREGISTERER, false)) {
                machine.useCustomDeregisterer(device);
            } else {
                machine.rest.asyncHttp.del("/push/deviceRegistrations?deviceId=" + device.id, HttpUtils.defaultAcceptHeaders(machine.rest.options.useBinaryProtocol), null, null, new Callback<Void>() {
                    @Override
                    public void onSuccess(Void response) {
                        Log.i(TAG, "deregistered " + device.id);
                        machine.handleEvent(new Deregistered());
                    }
                    @Override
                    public void onError(ErrorInfo reason) {
                        Log.e(TAG, "error deregistering " + device.id + ": " + reason.toString());
                        machine.handleEvent(new DeregistrationFailed(reason));
                    }
                });
            }
        }

        private final Context context;
        private final AblyRest rest;
        private final SharedPreferences prefs;
        private State current;
        private ArrayDeque<Event> pendingEvents;

        ActivationStateMachine(Context context, AblyRest rest) {
            this.context = context;
            this.rest = rest;
            this.prefs = PreferenceManager.getDefaultSharedPreferences(context);
            current = getPersistedState();
            pendingEvents = getPersistedPendingEvents();
        }

        public synchronized void handleEvent(Event event) {
            Log.d(TAG, String.format("handling event %s from %s", event.getClass().getSimpleName(), current.getClass().getSimpleName()));

            State maybeNext = current.transition(event);
            if (maybeNext == null) {
                Log.d(TAG, "enqueuing event: " + event.getClass().getSimpleName());
                pendingEvents.add(event);
                return;
            }

            Log.d(TAG, String.format("transition: %s -> %s", current.getClass().getSimpleName(), maybeNext.getClass().getSimpleName()));
            current = maybeNext;

            while (true) {
                Event pending = pendingEvents.peek();
                if (pending == null) {
                    break;
                }

                Log.d(TAG, "attempting to consume pending event: " + event.getClass().getSimpleName());

                maybeNext = current.transition(pending);
                if (maybeNext == null) {
                    break;
                }
                pendingEvents.poll();

                Log.d(TAG, String.format("transition: %s -> %s", current.getClass().getSimpleName(), maybeNext.getClass().getSimpleName()));
                current = maybeNext;
            }

            persist();
        }

        private void persist() {
            SharedPreferences.Editor editor = prefs.edit();

            if (current instanceof PersistentState) {
                // editor.putString(PersistKeys.CURRENT_STATE, current.getClass().getName());
                String className = current.getClass().getName();
                editor.putString(PersistKeys.CURRENT_STATE, className);
            }

            editor.putInt(PersistKeys.PENDING_EVENTS_LENGTH, pendingEvents.size());
            int i = 0;
            for (Event e : pendingEvents) {
                editor.putString(
                        String.format("%s[%d]", PersistKeys.PENDING_EVENTS_PREFIX, i),
                        e.getClass().getName()
                );

                i++;
            }

            boolean ok = editor.commit();
        }

        private State getPersistedState() {
            try {
                Class<State> stateClass = (Class<State>) Class.forName(prefs.getString(PersistKeys.CURRENT_STATE, ""));
                Constructor<State> constructor = stateClass.getConstructor(this.getClass());
                return constructor.newInstance(this);
            } catch (Exception e) {
                return new NotActivated(this);
            }
        }

        private ArrayDeque<Event> getPersistedPendingEvents() {
            int length = prefs.getInt(PersistKeys.PENDING_EVENTS_LENGTH, 0);
            ArrayDeque<Event> deque = new ArrayDeque<>(length);
            for (int i = 0; i < length; i++) {
                try {
                    String className = prefs.getString(String.format("%s[%d]", PersistKeys.PENDING_EVENTS_PREFIX, i), "");
                    Event event = ((Class<Event>) Class.forName(className)).newInstance();
                    deque.add(event);
                } catch(Exception e) {
                    throw new RuntimeException(e);
                }
            }
            return deque;
        }

        private static class PersistKeys {
            private static final String CURRENT_STATE = "ABLY_PUSH_CURRENT_STATE";
            private static final String PENDING_EVENTS_LENGTH = "ABLY_PUSH_PENDING_EVENTS_LENGTH";
            private static final String PENDING_EVENTS_PREFIX = "ABLY_PUSH_PENDING_EVENTS";
            private static final String USE_CUSTOM_REGISTERER = "ABLY_PUSH_USE_CUSTOM_REGISTERER";
            private static final String USE_CUSTOM_DEREGISTERER = "ABLY_PUSH_USE_CUSTOM_DEREGISTERER";
        }

        private static final String TAG = "AblyActivation";
    }

    private synchronized ActivationStateMachine getStateMachine(Context context) {
        if (ActivationStateMachine.INSTANCE == null) {
            ActivationStateMachine.INSTANCE = new ActivationStateMachine(context, rest);
        }
        return ActivationStateMachine.INSTANCE;
    }

    private static final String TAG = Push.class.getName();

}
