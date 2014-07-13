package ml.jlyu.un;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class SettingsActivity extends Activity {

	@Override
	protected void onCreate(Bundle savedInstanceState) {

		if (savedInstanceState == null) {
			SettingsFragment fragment = new SettingsFragment();
			getFragmentManager()
					.beginTransaction()
					.add(android.R.id.content, fragment,
							fragment.getClass().getSimpleName()).commit();
		}
		
		super.onCreate(savedInstanceState);

		this.startService(new Intent(this, UNoticeService.class));
	}
}
