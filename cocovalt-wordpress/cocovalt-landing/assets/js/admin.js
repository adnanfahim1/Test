/* Show the "Cocovalt Landing: page text" box only while the Cocovalt Landing template is selected. */
(function () {
	var template = window.cocovaltLandingTemplate;

	function toggle(selected) {
		var box = document.getElementById('cocovalt-landing-content');
		var display = selected === template ? '' : 'none';
		if (box && box.style.display !== display) {
			box.style.display = display;
		}
	}

	// Classic editor: Page Attributes → Template.
	var select = document.getElementById('page_template');
	if (select) {
		toggle(select.value);
		select.addEventListener('change', function () { toggle(select.value); });
		return;
	}

	// Block editor: Template panel in the page sidebar.
	if (window.wp && wp.data && wp.data.select('core/editor')) {
		wp.data.subscribe(function () {
			toggle(wp.data.select('core/editor').getEditedPostAttribute('template'));
		});
	}
})();
