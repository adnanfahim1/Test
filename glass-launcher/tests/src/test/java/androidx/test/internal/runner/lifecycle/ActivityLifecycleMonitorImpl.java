package androidx.test.internal.runner.lifecycle;
import androidx.test.runner.lifecycle.*;
import java.util.*;
public class ActivityLifecycleMonitorImpl implements ActivityLifecycleMonitor {
 private final Map<android.app.Activity, Stage> st = new WeakHashMap<android.app.Activity, Stage>(); private final List<ActivityLifecycleCallback> cbs = new ArrayList<ActivityLifecycleCallback>();
 public void addLifecycleCallback(ActivityLifecycleCallback c) { cbs.add(c); } public void removeLifecycleCallback(ActivityLifecycleCallback c) { cbs.remove(c); }
 public Stage getLifecycleStageOf(android.app.Activity a) { return st.get(a); }
 public Collection<android.app.Activity> getActivitiesInStage(Stage s) { List<android.app.Activity> r = new ArrayList<android.app.Activity>(); for (Map.Entry<android.app.Activity, Stage> e : st.entrySet()) if (e.getValue() == s) r.add(e.getKey()); return r; }
 public void signalLifecycleChange(Stage s, android.app.Activity a) { st.put(a, s); for (ActivityLifecycleCallback c : new ArrayList<ActivityLifecycleCallback>(cbs)) c.onActivityLifecycleChanged(a, s); } }
