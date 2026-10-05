<?php
/**
 * Editable content: field definitions, defaults and getters.
 *
 * Values live in post meta on the landing page. Until a field has been saved,
 * its default (the approved design copy) is used, so a freshly created page
 * already matches the design.
 *
 * @package CocovaltLanding
 */

defined( 'ABSPATH' ) || exit;

/**
 * Field definitions, in the order they appear in the editor.
 *
 * @return array[] key => { label, type (textarea|text|url), default, help, seo }
 */
function cocovalt_landing_fields() {
	$fields = array(
		'about'           => array(
			'label'   => __( 'Cocovalt description (hero, left panel)', 'cocovalt-landing' ),
			'type'    => 'textarea',
			'default' => 'Cocovalt is a specialized B2B chocolate brand operating under Pandughar Foods Ltd., established in 2026. Designed to deliver premium-grade cacao and chocolate formulations tailored for industrial and commercial applications, Cocovalt supplies food manufacturers, bakeries, confectioners, and food service partners with high-quality ingredients to elevate their product offerings.',
		),
		'group_lead'      => array(
			'label'   => __( 'Pandughar Group: paragraph 1 (larger lead text)', 'cocovalt-landing' ),
			'type'    => 'textarea',
			'default' => 'Pandughar Group is a conglomerate with a diverse portfolio spanning real estate, ready-made garments, composite textiles, consumer goods, IT, financial services, and agricultural products. The Group’s journey began in 1995 with Urban Design & Development Limited, a well-recognized real estate player in Dhaka. Over the past three decades, it has grown significantly, now employing more than 20,000 dedicated professionals.',
		),
		'group_p2'        => array(
			'label'   => __( 'Pandughar Group: paragraph 2', 'cocovalt-landing' ),
			'type'    => 'textarea',
			'default' => 'Central to our growth ambition is our deep commitment to our community responsibilities through our Pandughar Hasina Khanom Foundation. The Foundation supports community development by providing free, quality education to over 500 rural students, a monthly food program for more than 2,500 families, medical assistance, and access to safe drinking water, among other initiatives.',
		),
		'group_p3'        => array(
			'label'   => __( 'Pandughar Group: paragraph 3', 'cocovalt-landing' ),
			'type'    => 'textarea',
			'default' => 'Rooted in strong ethical principles and a people-first culture, Pandughar Group contributes to the economic development of Bangladesh while building a sustainable future for its stakeholders.',
		),
		'button_label'    => array(
			'label'   => __( 'Button label', 'cocovalt-landing' ),
			'type'    => 'text',
			'default' => 'Learn more',
			'help'    => __( 'Leave empty to hide the button.', 'cocovalt-landing' ),
		),
		'button_url'      => array(
			'label'   => __( 'Button link', 'cocovalt-landing' ),
			'type'    => 'url',
			'default' => '#',
			'help'    => __( 'The Pandughar Group website, e.g. https://example.com. "#" is a placeholder that links nowhere.', 'cocovalt-landing' ),
		),
		'seo_title'       => array(
			'label'   => __( 'Browser / search title', 'cocovalt-landing' ),
			'type'    => 'text',
			'default' => 'Cocovalt — Premium B2B Chocolate | Pandughar Foods Ltd.',
			'seo'     => true,
		),
		'seo_description' => array(
			'label'   => __( 'Meta description', 'cocovalt-landing' ),
			'type'    => 'textarea',
			'default' => 'Cocovalt is a specialized B2B chocolate brand by Pandughar Foods Ltd., supplying premium-grade cacao and chocolate formulations to food manufacturers, bakeries, confectioners and food service partners.',
			'help'    => __( 'Shown in search results and link previews. Plain text, about 150–160 characters.', 'cocovalt-landing' ),
			'seo'     => true,
		),
	);

	return apply_filters( 'cocovalt_landing_fields', $fields );
}

/**
 * Post meta key for a field.
 *
 * @param string $key Field key.
 * @return string
 */
function cocovalt_landing_meta_key( $key ) {
	return '_cocovalt_' . $key;
}

/**
 * Inline HTML allowed in paragraph fields.
 *
 * @return array
 */
function cocovalt_landing_allowed_html() {
	return array(
		'a'      => array(
			'href'   => true,
			'target' => true,
			'rel'    => true,
		),
		'strong' => array(),
		'b'      => array(),
		'em'     => array(),
		'i'      => array(),
		'br'     => array(),
	);
}

/**
 * Sanitize a submitted value for a field.
 *
 * @param string $key   Field key.
 * @param string $value Raw (unslashed) value.
 * @return string
 */
function cocovalt_landing_sanitize( $key, $value ) {
	$fields = cocovalt_landing_fields();
	$field  = isset( $fields[ $key ] ) ? $fields[ $key ] : array( 'type' => 'text' );
	$value  = trim( (string) $value );

	if ( 'url' === $field['type'] ) {
		return esc_url_raw( $value );
	}
	if ( 'textarea' === $field['type'] && empty( $field['seo'] ) ) {
		// wp_kses() stores a typed "&" as "&amp;", which the field would then show
		// literally. Keep the "&" as typed; output goes through wp_kses() again.
		return str_replace( '&amp;', '&', wp_kses( $value, cocovalt_landing_allowed_html() ) );
	}
	return sanitize_text_field( $value );
}

/**
 * Raw stored value for a field, or its default when it has never been saved.
 * Escape on output (see cocovalt_landing_html()).
 *
 * @param string   $key     Field key.
 * @param int|null $post_id Page ID; defaults to the queried page.
 * @return string
 */
function cocovalt_landing_get( $key, $post_id = null ) {
	$post_id  = $post_id ? (int) $post_id : (int) get_queried_object_id();
	$meta_key = cocovalt_landing_meta_key( $key );

	if ( $post_id && metadata_exists( 'post', $post_id, $meta_key ) ) {
		return (string) get_post_meta( $post_id, $meta_key, true );
	}

	$fields = cocovalt_landing_fields();
	return isset( $fields[ $key ]['default'] ) ? (string) $fields[ $key ]['default'] : '';
}

/**
 * A paragraph field as safe HTML (inline tags only).
 *
 * @param string   $key     Field key.
 * @param int|null $post_id Page ID.
 * @return string
 */
function cocovalt_landing_html( $key, $post_id = null ) {
	return wp_kses( cocovalt_landing_get( $key, $post_id ), cocovalt_landing_allowed_html() );
}
