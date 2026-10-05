<?php
/**
 * Page editor meta box for the landing page's text.
 *
 * The box is registered on every page but only shown (via admin.js) while the
 * "Cocovalt Landing" template is selected, and values are only saved for pages
 * that use the template.
 *
 * @package CocovaltLanding
 */

defined( 'ABSPATH' ) || exit;

function cocovalt_landing_add_meta_box() {
	add_meta_box(
		'cocovalt-landing-content',
		__( 'Cocovalt Landing: page text', 'cocovalt-landing' ),
		'cocovalt_landing_render_meta_box',
		'page',
		'normal',
		'high'
	);
}
add_action( 'add_meta_boxes_page', 'cocovalt_landing_add_meta_box' );

/**
 * @param WP_Post $post Page being edited.
 */
function cocovalt_landing_render_meta_box( $post ) {
	wp_nonce_field( 'cocovalt_landing_save', 'cocovalt_landing_nonce' );
	$seo_plugin = cocovalt_landing_seo_plugin_active();
	?>
	<p class="description">
		<?php esc_html_e( 'This template shows the text below, not the main editor content. Paragraph fields accept links, bold and italics (a, strong, em, br).', 'cocovalt-landing' ); ?>
	</p>
	<table class="form-table" role="presentation">
		<?php
		foreach ( cocovalt_landing_fields() as $key => $field ) :
			if ( ! empty( $field['seo'] ) && $seo_plugin ) {
				continue;
			}
			$id    = 'cocovalt-landing-' . $key;
			$name  = 'cocovalt_landing[' . $key . ']';
			$value = cocovalt_landing_get( $key, $post->ID );
			?>
			<tr>
				<th scope="row"><label for="<?php echo esc_attr( $id ); ?>"><?php echo esc_html( $field['label'] ); ?></label></th>
				<td>
					<?php if ( 'textarea' === $field['type'] ) : ?>
						<textarea id="<?php echo esc_attr( $id ); ?>" name="<?php echo esc_attr( $name ); ?>" rows="<?php echo empty( $field['seo'] ) ? 5 : 3; ?>" class="large-text"><?php echo esc_textarea( $value ); ?></textarea>
					<?php else : ?>
						<input type="text" id="<?php echo esc_attr( $id ); ?>" name="<?php echo esc_attr( $name ); ?>" value="<?php echo esc_attr( $value ); ?>" class="large-text">
					<?php endif; ?>
					<?php if ( ! empty( $field['help'] ) ) : ?>
						<p class="description"><?php echo esc_html( $field['help'] ); ?></p>
					<?php endif; ?>
				</td>
			</tr>
		<?php endforeach; ?>
	</table>
	<?php if ( $seo_plugin ) : ?>
		<p class="description"><?php esc_html_e( 'An SEO plugin is active, so set the page title and meta description in its settings for this page.', 'cocovalt-landing' ); ?></p>
	<?php endif; ?>
	<?php
}

/**
 * @param int $post_id Page ID.
 */
function cocovalt_landing_save_meta_box( $post_id ) {
	if ( ! isset( $_POST['cocovalt_landing_nonce'] ) || ! wp_verify_nonce( sanitize_key( $_POST['cocovalt_landing_nonce'] ), 'cocovalt_landing_save' ) ) {
		return;
	}
	if ( ( defined( 'DOING_AUTOSAVE' ) && DOING_AUTOSAVE ) || wp_is_post_revision( $post_id ) ) {
		return;
	}
	if ( ! current_user_can( 'edit_post', $post_id ) ) {
		return;
	}
	// The box is present (hidden) on every page; only store values for pages that use the template.
	if ( COCOVALT_LANDING_TEMPLATE !== get_page_template_slug( $post_id ) ) {
		return;
	}
	if ( empty( $_POST['cocovalt_landing'] ) || ! is_array( $_POST['cocovalt_landing'] ) ) {
		return;
	}

	$submitted = wp_unslash( $_POST['cocovalt_landing'] ); // phpcs:ignore WordPress.Security.ValidatedSanitizedInput.InputNotSanitized -- sanitized per field below.
	foreach ( array_keys( cocovalt_landing_fields() ) as $key ) {
		if ( isset( $submitted[ $key ] ) && is_string( $submitted[ $key ] ) ) {
			update_post_meta( $post_id, cocovalt_landing_meta_key( $key ), cocovalt_landing_sanitize( $key, $submitted[ $key ] ) );
		}
	}
}
add_action( 'save_post_page', 'cocovalt_landing_save_meta_box' );

/**
 * @param string $hook_suffix Current admin screen.
 */
function cocovalt_landing_admin_assets( $hook_suffix ) {
	if ( ! in_array( $hook_suffix, array( 'post.php', 'post-new.php' ), true ) || 'page' !== get_current_screen()->post_type ) {
		return;
	}
	wp_enqueue_script(
		'cocovalt-landing-admin',
		COCOVALT_LANDING_URL . 'assets/js/admin.js',
		array(),
		COCOVALT_LANDING_VERSION,
		true
	);
	wp_add_inline_script(
		'cocovalt-landing-admin',
		'window.cocovaltLandingTemplate = ' . wp_json_encode( COCOVALT_LANDING_TEMPLATE ) . ';',
		'before'
	);
}
add_action( 'admin_enqueue_scripts', 'cocovalt_landing_admin_assets' );
