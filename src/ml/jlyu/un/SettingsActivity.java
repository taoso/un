package ml.jlyu.un;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class SettingsActivity extends Activity {

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		if (savedInstanceState == null) {
			SettingsFragment fragment = new SettingsFragment();
			getFragmentManager().beginTransaction()
					.add(android.R.id.content, fragment, fragment.getClass().getSimpleName())
					.commit();
		}
		
        this.startService(new Intent(this, UNoticeService.class));
	}
}
