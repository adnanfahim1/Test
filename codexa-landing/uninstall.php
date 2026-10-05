<?php
/**
 * Remove plugin options on uninstall. Pages created by the plugin are kept.
 *
 * @package CodexaLanding
 */

if ( ! defined( 'WP_UNINSTALL_PLUGIN' ) ) {
	exit;
}

delete_option( 'codexa_landing_settings' );
delete_option( 'codexa_landing_setup_notice' );
