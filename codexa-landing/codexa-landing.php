<?php
/**
 * Plugin Name:       Codexa Landing
 * Description:       The Codexa coming-soon landing page (hero, about, liquid-glass coming soon) as a full-width page template and a [codexa_landing] shortcode.
 * Version:           1.0.0
 * Requires at least: 5.8
 * Requires PHP:      7.4
 * Author:            Pandughar Group
 * License:           GPL-2.0-or-later
 * License URI:       https://www.gnu.org/licenses/gpl-2.0.html
 * Text Domain:       codexa-landing
 * Domain Path:       /languages
 *
 * @package CodexaLanding
 */

if ( ! defined( 'ABSPATH' ) ) {
	exit;
}

define( 'CODEXA_LANDING_VERSION', '1.0.0' );
define( 'CODEXA_LANDING_FILE', __FILE__ );
define( 'CODEXA_LANDING_DIR', plugin_dir_path( __FILE__ ) );

require_once CODEXA_LANDING_DIR . 'includes/class-codexa-settings.php';
require_once CODEXA_LANDING_DIR . 'includes/class-codexa-landing.php';

register_activation_hook( __FILE__, array( 'Codexa_Landing', 'activate' ) );
register_deactivation_hook( __FILE__, array( 'Codexa_Landing', 'deactivate' ) );

add_action( 'plugins_loaded', array( 'Codexa_Landing', 'init' ) );
