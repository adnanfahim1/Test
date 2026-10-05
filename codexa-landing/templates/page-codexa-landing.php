<?php
/**
 * Blank-canvas page template: wp_head()/wp_footer() but no theme header, footer or containers.
 *
 * @package CodexaLanding
 */

if ( ! defined( 'ABSPATH' ) ) {
	exit;
}
?><!doctype html>
<html <?php language_attributes(); ?>>
<head>
<meta charset="<?php bloginfo( 'charset' ); ?>">
<?php wp_head(); ?>
</head>
<body <?php body_class( 'cdx-landing-template' ); ?>>
<?php
wp_body_open();
?>
<main id="cdx-main">
<?php
while ( have_posts() ) {
	the_post();
	echo Codexa_Landing::render(); // phpcs:ignore WordPress.Security.EscapeOutput.OutputNotEscaped -- escaped in the partial.
}
?>
</main>
<?php
wp_footer();
?>
</body>
</html>
