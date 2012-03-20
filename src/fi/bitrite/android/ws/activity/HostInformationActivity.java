package fi.bitrite.android.ws.activity;

import roboguice.activity.RoboActivity;
import roboguice.inject.InjectView;
import roboguice.util.Strings;
import android.app.Dialog;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.inject.Inject;

import fi.bitrite.android.ws.R;
import fi.bitrite.android.ws.auth.http.HttpAuthenticationService;
import fi.bitrite.android.ws.auth.http.HttpSessionContainer;
import fi.bitrite.android.ws.host.impl.HttpHostInformation;
import fi.bitrite.android.ws.model.Host;
import fi.bitrite.android.ws.persistence.StarredHostDao;

public class HostInformationActivity extends RoboActivity {

	public static final int RESULT_SHOW_HOST_ON_MAP = RESULT_FIRST_USER + 1;
	
	private static final int NO_ID = 0;

	@InjectView(R.id.layoutHostDetails)
	LinearLayout hostDetails;

	@InjectView(R.id.btnHostStar) ImageView star;
	@InjectView(R.id.txtHostFullname) TextView fullname;
	@InjectView(R.id.txtHostComments) TextView comments;
	@InjectView(R.id.txtHostLocation) TextView location;
	@InjectView(R.id.txtHostMobilePhone) TextView mobilePhone;
	@InjectView(R.id.txtHostHomePhone) TextView homePhone;
	@InjectView(R.id.txtHostWorkPhone) TextView workPhone;
	@InjectView(R.id.txtPreferredNotice) TextView preferredNotice;
	@InjectView(R.id.txtMaxGuests) TextView maxGuests;
	@InjectView(R.id.txtNearestAccomodation) TextView nearestAccomodation;
	@InjectView(R.id.txtCampground) TextView campground;
	@InjectView(R.id.txtBikeShop) TextView bikeShop;
	@InjectView(R.id.txtServices) TextView services;

	@Inject HttpAuthenticationService authenticationService;
	@Inject HttpSessionContainer sessionContainer;

	@Inject StarredHostDao starredHostDao;

	private Host host;
	private int id;
	private boolean starred;
	private boolean forceUpdate;

	private HostInformationTask hostInfoTask;
	
	private DialogHandler dialogHandler;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.host_information);
		starredHostDao.open();

		dialogHandler = new DialogHandler(HostInformationActivity.this);
		boolean inProgress = DialogHandler.inProgress();
		boolean shouldDownloadHostInfo = true;
		forceUpdate = false;
		
		if (savedInstanceState != null) {
			host = savedInstanceState.getParcelable("host");
			id = savedInstanceState.getInt("id");
			forceUpdate = savedInstanceState.getBoolean("force_update");
			starred = starredHostDao.isHostStarred(id, host.getName());
			shouldDownloadHostInfo = inProgress;
		} else {
			Intent i = getIntent();
			host = (Host) i.getParcelableExtra("host");
			id = i.getIntExtra("id", NO_ID);
			starred = starredHostDao.isHostStarred(id, host.getName());
			if (starred) {
				host = starredHostDao.get(id, host.getName());
				forceUpdate = i.getBooleanExtra("update", false);
				shouldDownloadHostInfo = forceUpdate;
			} else {
				shouldDownloadHostInfo = true;
			}
		}

		if (shouldDownloadHostInfo) {
			getHostInformationAsync();
		} else {
			setViewContentFromHost();
		}
		
		setupStar();
		fullname.setText(host.getFullname());
		
		starredHostDao.close();
	}
	
	@Override
	protected void onSaveInstanceState(Bundle outState) {
		outState.putParcelable("host", host);
		outState.putInt("id", id);
		outState.putBoolean("force_update", forceUpdate);
		
		if (hostInfoTask != null) {
			hostInfoTask.cancel(false);
		}
		
		super.onSaveInstanceState(outState);
	}
	
	private void setupStar() {
		int drawable = starred ? R.drawable.starred_on : R.drawable.starred_off;
		star.setImageDrawable(getResources().getDrawable(drawable));
		star.setVisibility(View.VISIBLE);
	}
	
	public void showStarHostDialog(View view) {
		toggleHostStarred();
		int msgId = (starred ? R.string.host_starred : R.string.host_unstarred);
		Toast.makeText(this, getResources().getString(msgId), Toast.LENGTH_LONG).show();
	}

	protected void toggleHostStarred() {
		starredHostDao.open();
		if (starredHostDao.isHostStarred(id, host.getName())) {
			starredHostDao.delete(id, host.getName());
		} else {
			starredHostDao.insert(id, host.getName(), host);
		}
		
		starred = !starred;
		setupStar();
		starredHostDao.close();
	}
	
	public void contactHost(View view) {
		Intent i = new Intent(HostInformationActivity.this, HostContactActivity.class);
		i.putExtra("host", host);
		i.putExtra("id", id);
		startActivity(i);
	}
	
	public void showHostOnMap(View view) {
		// We need to finish the host info dialog, switch to the map tab and 
		// zoom/scroll to the location of the host
		Intent resultIntent = new Intent();
		
		if (!Strings.isEmpty(host.getLatitude()) && !Strings.isEmpty(host.getLongitude())) {
			int lat = (int) Math.round(Float.parseFloat(host.getLatitude()) * 1.0e6);
			int lon = (int) Math.round(Float.parseFloat(host.getLongitude()) * 1.0e6);
			resultIntent.putExtra("lat", lat);
			resultIntent.putExtra("lon", lon);
		}
		
		setResult(RESULT_SHOW_HOST_ON_MAP, resultIntent);
		finish();
	}

	@Override
	protected Dialog onCreateDialog(int id, Bundle args) {
		if (DialogHandler.inProgress()) {
			return dialogHandler.createDialog(id, getResources().getString(R.string.host_info_in_progress));
		} else {
			return null;
		}
	}

	private void getHostInformationAsync() {
		dialogHandler.showDialog(DialogHandler.HOST_INFORMATION);
		hostInfoTask = new HostInformationTask();
		hostInfoTask.execute();
	}

	private void setViewContentFromHost() {
		comments.setText(host.getComments());
		location.setText(host.getLocation());
		mobilePhone.setText(host.getMobilePhone());
		homePhone.setText(host.getHomePhone());
		workPhone.setText(host.getWorkPhone());
		preferredNotice.setText(host.getPreferredNotice());
		maxGuests.setText(host.getMaxCyclists());
		nearestAccomodation.setText(host.getMotel());
		campground.setText(host.getCampground());
		bikeShop.setText(host.getBikeshop());
		services.setText(host.getServices());

		hostDetails.setVisibility(View.VISIBLE);
	}

	private class HostInformationTask extends AsyncTask<Void, Void, Object> {
		
		@Override
		protected Object doInBackground(Void... params) {
			Object retObj = null;
			
			try {
				HttpHostInformation hostInfo = new HttpHostInformation(authenticationService, sessionContainer);
				
				if (id == NO_ID) {
					id = hostInfo.getHostId(host.getName());
				}
				
				host = hostInfo.getHostInformation(id);
			}
			
			catch (Exception e) {
				Log.e("WSAndroid", e.getMessage(), e);
				retObj = e;
			}
			
			return retObj;
		}
		
		@Override
		protected void onPostExecute(Object result) {
			dialogHandler.dismiss();
			
			if (result instanceof Exception) {
				dialogHandler.alert(getResources().getString(R.string.error_retrieving_host_information));
				return;
			}
			
			setViewContentFromHost();
			
			if (starred && forceUpdate) {
				starredHostDao.open();
				starredHostDao.update(id, host.getName(), host);
				starredHostDao.close();
				dialogHandler.alert(getResources().getString(R.string.host_updated));
			}

			if (host.isNotCurrentlyAvailable()) {
				dialogHandler.alert(getResources().getString(R.string.host_not_available));
			}
		}

	}
	
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
	    MenuInflater inflater = getMenuInflater();
	    inflater.inflate(R.menu.host_information_menu, menu);
	    return true;
	}
	
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.menuMap:
			showHostOnMap(null);
			return true;
		case R.id.menuStar:
			showStarHostDialog(null);
			return true;
		case R.id.menuUpdate:
			Intent i = new Intent();
			i.putExtra("host", host);
			i.putExtra("id", id);
			i.putExtra("update", true);
			setIntent(i);
			onCreate(null);
			return true;
		default:
			return super.onOptionsItemSelected(item);
		}
	}
}
